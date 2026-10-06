package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import scala.util.Try

import io.circe.*
import io.circe.syntax.*

import com.chipprbots.ethereum.utils.Hex

/** The contract work derived from every account downloaded so far: the storage-task file (64-byte entries: account hash
  * + storage root) and the unique-codeHash file (32-byte entries), each with the number of entries that are valid.
  * Entries past a count are ignored — they belong to accounts above the cursors, which are downloaded (and
  * re-identified) again.
  */
final case class ContractTaskFiles(
    storagePath: String,
    storageCount: Long,
    codeHashesPath: String,
    codeHashesCount: Long
):

  /** Both files exist and hold at least `count` whole entries. A file may be LONGER than its count (it keeps being
    * appended to after a checkpoint), never shorter.
    */
  def validate(): Either[String, Unit] =
    def check(path: String, count: Long, entrySize: Int, what: String): Either[String, Unit] =
      Try {
        val p = Paths.get(path)
        if !Files.isRegularFile(p) then Left(s"$what file $path is missing")
        else
          val size = Files.size(p)
          if size < count * entrySize then
            Left(s"$what file $path holds $size bytes, fewer than $count entries x $entrySize")
          else Right(())
      }.fold(e => Left(s"$what file $path unreadable: ${e.getMessage}"), identity)
    for
      _ <- if storageCount >= 0 && codeHashesCount >= 0 then Right(()) else Left("negative entry count")
      _ <- check(storagePath, storageCount, StorageTaskFile.EntrySize, "storage-task")
      _ <- check(codeHashesPath, codeHashesCount, StorageTaskFile.CodeHashEntrySize, "codeHash")
    yield ()

/** Versioned account-phase resume checkpoint, persisted under a fixed AppStateStorage key.
  *
  * `cursors` maps each range's `last` boundary to its `next` cursor (`next >= last` means the range is complete). The
  * invariant tying cursors to `taskFiles`: the first `storageCount` / `codeHashesCount` entries are exactly the
  * contract work of the accounts below the cursors. The account coordinator captures both in a single actor step.
  *
  * `stateRoot` / `pivotBlock` record the root the cursors were last advanced against. A range may straddle several
  * roots (pivot refreshes, restarts); correctness never depends on that root — the healing walk from the final pivot
  * root re-fetches every node that differs (see AccountRangeCoordinator, "resume from a mid-range cursor").
  */
final case class AccountResumeCheckpoint(
    stateRoot: ByteString,
    pivotBlock: BigInt,
    cursors: Map[ByteString, ByteString],
    taskFiles: ContractTaskFiles
)

object AccountResumeCheckpoint:

  /** Bump on any incompatible change. A record with any other version is ignored (the account phase starts fresh). */
  val CurrentVersion: Int = 1

  private def hex(bs: ByteString): String = Hex.toHexString(bs.toArray)
  private def unhex(s: String): ByteString = ByteString(Hex.decode(s))

  def encode(c: AccountResumeCheckpoint): String =
    Json
      .obj(
        "version" -> CurrentVersion.asJson,
        "stateRoot" -> hex(c.stateRoot).asJson,
        "pivotBlock" -> c.pivotBlock.toString.asJson,
        "cursors" -> c.cursors.map { case (k, v) => hex(k) -> hex(v) }.asJson,
        "storagePath" -> c.taskFiles.storagePath.asJson,
        "storageCount" -> c.taskFiles.storageCount.asJson,
        "codeHashesPath" -> c.taskFiles.codeHashesPath.asJson,
        "codeHashesCount" -> c.taskFiles.codeHashesCount.asJson
      )
      .noSpaces

  def decode(json: String): Either[String, AccountResumeCheckpoint] =
    io.circe.parser.parse(json).left.map(e => s"malformed JSON: ${e.getMessage}").flatMap { j =>
      val c = j.hcursor
      c.downField("version").as[Int] match
        case Left(_)                         => Left("no version field")
        case Right(v) if v != CurrentVersion => Left(s"unsupported version $v")
        case Right(_) =>
          val fields = for
            root <- c.downField("stateRoot").as[String]
            pivot <- c.downField("pivotBlock").as[String]
            cursors <- c.downField("cursors").as[Map[String, String]]
            storagePath <- c.downField("storagePath").as[String]
            storageCount <- c.downField("storageCount").as[Long]
            codePath <- c.downField("codeHashesPath").as[String]
            codeCount <- c.downField("codeHashesCount").as[Long]
          yield (root, pivot, cursors, ContractTaskFiles(storagePath, storageCount, codePath, codeCount))
          fields.left
            .map(e => s"missing field: ${e.getMessage}")
            .flatMap { case (root, pivot, cursors, files) =>
              Try(
                AccountResumeCheckpoint(
                  stateRoot = unhex(root),
                  pivotBlock = BigInt(pivot),
                  cursors = cursors.map { case (k, v) => unhex(k) -> unhex(v) },
                  taskFiles = files
                )
              ).toEither.left.map(e => s"bad field value: ${e.getMessage}")
            }
    }
