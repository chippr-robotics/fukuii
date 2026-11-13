package com.chipprbots.ethereum.ephemery

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.regex.Pattern

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.ephemery.ReleaseLayout.FileOps

/** The node's Ephemery root on disk (spec 028 R6, data-model.md "Ephemery root and its entries"):
  * {{{
  *   <root>/releases/ephemery-N/   a ReleaseEntry: chains/, release.json, testnet/?   (immutable once in place)
  *   <root>/ephemery-N/            the node's datadir for iteration N
  *   <root>/current                a symlink to releases/ephemery-N, the last selected
  *   <root>/.stage-<random>/       work directories, swept when older than an hour
  * }}}
  * An entry is built in a stage and renamed into place only when its target is absent, so a failure leaves nothing
  * partial. Nothing under `releases/` and no datadir is ever deleted here: an operator may be using them.
  *
  * File I/O only: no `Config`, and the clock is the caller's.
  */
final class ReleaseStore(root: Path, clock: Clock, ops: FileOps = FileOps.Default):
  import ReleaseStore.*

  private val releases = root.resolve("releases")

  def releaseDir(iteration: Int): Path = releases.resolve(s"ephemery-$iteration")
  def datadir(iteration: Int): Path = root.resolve(s"ephemery-$iteration")

  /** The entries that re-validate, and a line for each directory that does not (it is invisible, never deleted). */
  def list(): Listing =
    if !Files.isDirectory(releases) then Listing(Nil, Nil)
    else
      val dirs = children(releases).filter(p => EntryName.matcher(p.getFileName.toString).matches())
      val loaded = dirs.map(d => load(d))
      Listing(
        loaded.collect { case Right(h) => h }.sortBy(_.entry.iteration),
        loaded.collect { case Left(problem) => problem }
      )

  /** Puts `entry` at `releases/ephemery-N` with one rename, after the stage's files and directories are on disk. The
    * same hash already there is [[AlreadyPresent]] and the stage is discarded; another hash is `Republished`. A
    * directory there that is not a valid entry (a torn write, a hand edit) is renamed to
    * `releases/.invalid-ephemery-N-<k>`, never deleted, and the entry is placed ([[SetAside]]).
    */
  def place(entry: ReleaseEntry): Either[ReleaseRefusal, PlaceResult] =
    withStage { stage =>
      val chains = stage.resolve(ReleaseEntry.ChainsDir)
      StageSupport.writeDurable(chains.resolve(EphemeryRelease.ChainConfFileName), entry.chainConf)
      StageSupport.writeDurable(chains.resolve(EphemeryRelease.GenesisFileName), entry.genesisJson)
      StageSupport.writeDurable(stage.resolve(ReleaseEntry.MetadataFile), entry.metadata.toJson)
      StageSupport.syncDir(chains)
      StageSupport.syncDir(stage)
      val target = releaseDir(entry.iteration)
      val placed: Either[ReleaseRefusal, PlaceResult] =
        if Files.exists(target, LinkOption.NOFOLLOW_LINKS) then
          load(target) match
            case Right(held) if held.entry.genesisHash == entry.genesisHash => Right(AlreadyPresent)
            case Right(held) =>
              Left(ReleaseRefusal.Republished(entry.iteration, held.entry.genesisHash, entry.genesisHash))
            case Left(_) =>
              val aside = freeInvalidName(entry.iteration)
              ops.move(target, aside)
              ops.move(stage, target)
              Right(SetAside(aside))
        else
          Files.createDirectories(releases)
          ops.move(stage, target)
          Right(Placed)
      StageSupport.syncDir(releases)
      placed
    }

  /** `releases/.invalid-ephemery-N-<k>`, the first `k` from 1 that is free. */
  private def freeInvalidName(iteration: Int): Path =
    Iterator
      .from(1)
      .map(k => releases.resolve(s".invalid-ephemery-$iteration-$k"))
      .find(p => !Files.exists(p, LinkOption.NOFOLLOW_LINKS))
      .get

  /** Adds `testnet/` (the consensus-layer directory) to an existing entry, with one rename, if it has none. `true` when
    * it was added, `false` when it was there already.
    */
  def addTestnet(iteration: Int, from: Path): Either[String, Boolean] =
    val entry = releaseDir(iteration)
    withStage { stage =>
      if !Files.isDirectory(entry) then Left(s"$entry does not exist")
      else if Files.exists(entry.resolve("testnet"), LinkOption.NOFOLLOW_LINKS) then Right(false)
      else
        StageSupport.copyMembers(from, stage.resolve("testnet"))
        ops.move(stage.resolve("testnet"), entry.resolve("testnet"))
        Right(true)
    }.left.map(_.message)

  /** Removes the `.stage-*` directories of runs that died: older than an hour, and holding no old copy. */
  def sweepStages(): Unit =
    if Files.isDirectory(root) then
      // Under the root's lock, so it cannot remove the stage of a writer that is running; held elsewhere, skip it.
      Try(StageSupport.acquire(root)).toOption.flatten.foreach { lock =>
        try StageSupport.sweepStale(root, clock.instant())
        finally StageSupport.release(Some(lock))
      }

  /** The datadir of iteration N, made when it is new. A new one gets `<root>/static-nodes.json` when the root has it (a
    * copy that fails is a warning, not a failure: the node starts without it); one that exists is the node's, and is
    * left as it is.
    */
  def seedDatadir(iteration: Int): Seeded =
    val dir = datadir(iteration)
    if Files.exists(dir, LinkOption.NOFOLLOW_LINKS) then Seeded(dir, None)
    else
      Files.createDirectories(dir)
      val seed = root.resolve(StaticNodesFile)
      if !Files.isRegularFile(seed) then Seeded(dir, None)
      else
        try
          Files.copy(seed, dir.resolve(StaticNodesFile))
          Seeded(dir, None)
        catch
          case _: FileAlreadyExistsException => Seeded(dir, None)
          case NonFatal(e) =>
            Seeded(dir, Some(s"cannot copy $seed into $dir: ${e.getMessage}; the node starts without it"))

  /** The other iterations' datadirs, by iteration: names only. Sizing them would walk a database (tens of thousands of
    * files) at every start.
    */
  def earlierDatadirs(except: Int): List[String] =
    children(root)
      .filter(p =>
        DatadirName.matcher(p.getFileName.toString).matches() && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
      )
      .filter(_.getFileName.toString != s"ephemery-$except")
      .sortBy(p => p.getFileName.toString.stripPrefix("ephemery-").toInt)
      .map(_.getFileName.toString)

  /** A datadir from before built-in Ephemery: RocksDB straight under the root. Never used, never touched. */
  def legacyDatadir(): Option[Path] =
    Option(root.resolve("rocksdb")).filter(Files.isDirectory(_, LinkOption.NOFOLLOW_LINKS))

  /** `current -> releases/ephemery-N`. A `current` that is not a symlink is the operator's and is left alone. */
  def pointCurrent(iteration: Int): Either[String, Unit] =
    val current = root.resolve("current")
    if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current) then
      Left(s"$current exists and is not a symlink; leaving it alone")
    else
      try Right(StageSupport.point(current, s"releases/ephemery-$iteration", ops))
      catch case NonFatal(e) => Left(s"cannot point $current at releases/ephemery-$iteration: ${e.getMessage}")

  // ---- internals --------------------------------------------------------------------------------------------------

  /** Runs `body` with a fresh `.stage-*` directory under the root and the root's lock held, and removes the stage
    * after. An I/O failure is a `CannotStore` refusal.
    */
  private def withStage[A](body: Path => Either[?, A]): Either[ReleaseRefusal, A] =
    var stage: Option[Path] = None
    var lock: Option[(java.nio.channels.FileChannel, java.nio.channels.FileLock)] = None
    try
      Files.createDirectories(root)
      lock = StageSupport.acquire(root)
      if lock.isEmpty then Left(ReleaseRefusal.CannotStore(s"another fukuii is writing to $root"))
      else
        val staged = Files.createTempDirectory(root, ".stage-")
        stage = Some(staged)
        // createTempDirectory makes it 0700, and the entry directory would inherit that mode.
        Try(Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x")))
        body(staged).left.map {
          case r: ReleaseRefusal => r
          case other             => ReleaseRefusal.CannotStore(other.toString)
        }
    catch case NonFatal(e) => Left(ReleaseRefusal.CannotStore(s"${e.getMessage}"))
    finally
      stage.foreach(s => Try(StageSupport.deleteTree(s)))
      StageSupport.release(lock)

  private def children(dir: Path): List[Path] =
    if !Files.isDirectory(dir) then Nil
    else
      val s = Files.list(dir)
      try s.iterator().asScala.toList
      finally s.close()

  /** Reads and re-validates one entry directory. `Left` is the line for the log. */
  private def load(dir: Path): Either[String, Held] =
    val name = dir.getFileName.toString
    def text(rel: String): Either[String, String] =
      val file = dir.resolve(rel)
      if !Files.isRegularFile(file) then Left(s"$name: $rel is missing")
      else if Files.size(file) > ReleaseInputs.MaxMemberBytes then Left(s"$name: $rel is too large")
      else
        Try(Files.readAllBytes(file)).toEither.left
          .map(e => s"$name: cannot read $rel: ${e.getMessage}")
          .flatMap(bytes => ReleaseInputs.decode(bytes, rel).left.map(r => s"$name: ${r.message}"))
    val verified = for
      metadata <- text(ReleaseEntry.MetadataFile)
      conf <- text(s"${ReleaseEntry.ChainsDir}/${EphemeryRelease.ChainConfFileName}")
      genesis <- text(s"${ReleaseEntry.ChainsDir}/${EphemeryRelease.GenesisFileName}")
      entry <- ReleaseEntry.verify(metadata, conf, genesis).left.map(e => s"$name: $e")
      _ <- Either.cond(entry.release == name, (), s"$name: release.json says ${entry.release}")
    yield entry
    verified.map { entry =>
      val testnet = dir.resolve("testnet")
      Held(entry, dir, Option.when(Files.isDirectory(testnet))(testnet))
    }

object ReleaseStore:

  val StaticNodesFile: String = "static-nodes.json"

  private val EntryName = Pattern.compile("ephemery-[0-9]+")
  private val DatadirName = Pattern.compile("ephemery-[0-9]{1,9}")

  sealed trait PlaceResult extends Product with Serializable

  /** The entry was not there and is now. */
  case object Placed extends PlaceResult

  /** The entry is placed; an invalid directory that was in its way was renamed to `aside` (kept, not deleted). */
  final case class SetAside(aside: Path) extends PlaceResult

  /** @param warning
    *   why the static nodes were not copied, when they were not
    */
  final case class Seeded(dir: Path, warning: Option[String])

  /** The same iteration with the same genesis hash was there: the stage was discarded. */
  case object AlreadyPresent extends PlaceResult

  /** A valid entry as found on disk. */
  final case class Held(entry: ReleaseEntry, dir: Path, testnetDir: Option[Path]):
    def chainsDir: Path = dir.resolve(ReleaseEntry.ChainsDir)

  /** @param problems
    *   a line for each `releases/ephemery-N` directory that is invisible because it does not re-validate
    */
  final case class Listing(entries: List[Held], problems: List[String])
