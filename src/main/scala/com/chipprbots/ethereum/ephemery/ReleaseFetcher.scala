package com.chipprbots.ethereum.ephemery

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.regex.Pattern

import scala.util.control.NonFatal

import com.chipprbots.ethereum.ephemery.EphemeryRelease.ValidRelease
import com.chipprbots.ethereum.ephemery.StrictJson.*

/** Fetches one Ephemery release over HTTPS (spec 028 R7), validates it, and stages its consensus-layer files.
  *
  * The transport makes one attempt per call; the retries are here, and every attempt and every wait sits inside the one
  * `budget` (`fukuii.ephemery.refresh-timeout`), measured on the injected clock and waited with the injected sleeper:
  * nothing here reads the system clock or sleeps on a thread. Each attempt gets its own absolute deadline, no later
  * than the end of the budget.
  *
  *   - Retried (up to [[ReleaseFetcher.Attempts]] attempts, backing off [[ReleaseFetcher.Backoffs]]): an I/O failure, a
  *     timeout, a 5xx, a 429, a 403 from the GitHub API (its rate limit), and a 404, which means "not published yet".
  *   - Permanent, so the first one ends the fetch: every other refusal of the transport (a host off the allow-list, a
  *     non-https hop, too many redirects, a body over the cap, an unexpected status).
  *
  * The archive is read by [[StrictTarReader]]: the execution-layer members stay in memory and go through
  * [[EphemeryRelease.validate]]; the rest is staged, without `parsedBeaconState.json` and `bootnode-keys/`.
  *
  * @param base
  *   the source's base URL (a trailing slash is ignored): a release is
  *   `<base>/releases/download/ephemery-N/testnet-all.tar.gz`
  */
final class ReleaseFetcher(
    transport: ReleaseTransport,
    clock: Clock,
    sleeper: Sleeper,
    base: URI,
    budget: Duration
):
  import ReleaseFetcher.*

  /** The base without a trailing slash, as text. */
  private val root: String = base.toString.stripSuffix("/")

  /** The release whose window contains now, starting from the target `target` (R7): the asset of `target`, and, if its
    * window does not contain now, the one API call that names the latest release. Refused with
    * [[ReleaseRefusal.WindowMismatch]] (69) when neither does. A 404 on the computed target gets no API fallback, by
    * design: the release is not out yet (the usual case), and the fallback is for a target whose own window is wrong.
    *
    * @param newStage
    *   makes an empty directory for one archive; this may be called twice
    * @param known
    *   the genesis hash this node already holds for an iteration: another hash for the same iteration is
    *   [[ReleaseRefusal.Republished]]
    */
  def fetchCurrent(
      target: Int,
      newStage: () => Path,
      known: Int => Option[String] = _ => None
  ): Either[ReleaseRefusal, Fetched] =
    val overall = clock.instant().plus(budget)
    for
      first <- fetchTag(s"ephemery-$target", target, newStage, known, overall)
      current <-
        if containsNow(first.release) then Right(first)
        else
          val window =
            s"${first.release.release} covers ${instant(first.release.genesisTimestamp)} to ${instant(first.release.resetTime)}"
          emptyStage(first.stage)
          latest(first.release, window, newStage, known, overall)
    yield current

  /** The named release, whatever its window: the operator asked for it. Only `ephemery-fetch --release` uses it; the
    * node's startup must go through [[fetchCurrent]], which checks the window, so this is not visible outside the
    * package.
    */
  private[ephemery] def fetchExact(
      iteration: Int,
      newStage: () => Path,
      known: Int => Option[String] = _ => None
  ): Either[ReleaseRefusal, Fetched] =
    fetchTag(s"ephemery-$iteration", iteration, newStage, known, clock.instant().plus(budget))

  // ---- the API fallback ---------------------------------------------------------------------------------------------

  /** `<api>/repos/<owner>/<repo>/releases/latest`, for a GitHub source only: a mirror has no such API. */
  private def apiLatest: Option[URI] =
    Option.when(base.getHost != null && base.getHost.equalsIgnoreCase("github.com")) {
      new URI(s"https://api.github.com/repos${base.getPath.stripSuffix("/")}/releases/latest")
    }

  private def latest(
      first: ValidRelease,
      window: String,
      newStage: () => Path,
      known: Int => Option[String],
      overall: Instant
  ): Either[ReleaseRefusal, Fetched] =
    val mismatch: String => ReleaseRefusal = why =>
      ReleaseRefusal.WindowMismatch(first.iteration, s"$window ($why)", clock.instant().toString)
    apiLatest match
      case None => Left(mismatch("this source has no API to ask for a newer release"))
      case Some(api) =>
        get(
          api,
          MaxApiBytes,
          Some(ApiAccept),
          overall,
          isApi = true,
          notFound = mismatch("the API has no release")
        ).left
          .map(r => mismatch(s"the API could not be asked: ${r.message}"))
          .flatMap(body => tagOf(body.bytes).toRight(mismatch("the API named no ephemery release")))
          .flatMap { tag =>
            if tag == first.release then Left(mismatch(s"the API's latest is $tag too"))
            else
              fetchTag(tag, tag.stripPrefix("ephemery-").toInt, newStage, known, overall).flatMap { second =>
                if containsNow(second.release) then Right(second)
                else
                  emptyStage(second.stage)
                  Left(
                    mismatch(
                      s"the API's latest $tag covers ${instant(second.release.genesisTimestamp)} to ${instant(second.release.resetTime)}"
                    )
                  )
              }
          }

  /** The `tag_name` of a release object, when it is an `ephemery-N` tag. */
  private def tagOf(bytes: Array[Byte]): Option[String] =
    ReleaseInputs
      .decode(bytes, "the API answer")
      .toOption
      .flatMap(t => StrictJson.parse(t).toOption)
      .collect { case o: JObj =>
        o
      }
      .flatMap(_.get("tag_name"))
      .collect { case JStr(s) => s }
      .filter(t => TagName.matcher(t).matches())

  // ---- one release --------------------------------------------------------------------------------------------------

  private def fetchTag(
      tag: String,
      iteration: Int,
      newStage: () => Path,
      known: Int => Option[String],
      overall: Instant
  ): Either[ReleaseRefusal, Fetched] =
    val url = new URI(s"$root/releases/download/$tag/testnet-all.tar.gz")
    get(
      url,
      MaxArchiveBytes,
      None,
      overall,
      isApi = false,
      // a mirror has no API to cross-check with: a mistyped source looks exactly like "not published yet"
      notFound = ReleaseRefusal.NotYetPublished(iteration, wrongSourcePossible = apiLatest.isEmpty)
    )
      .flatMap(body => unpack(body, tag, newStage(), known))

  private def unpack(
      body: FetchedBody,
      tag: String,
      stage: Path,
      known: Int => Option[String]
  ): Either[ReleaseRefusal, Fetched] =
    val read = StrictTarReader.read(new ByteArrayInputStream(body.bytes), ReleaseInputs.MemberNames, stage, TarExcludes)
    val result: Either[ReleaseRefusal, Fetched] = read match
      case Left(refusal) => Left(ReleaseRefusal.CannotRepresent("the release archive", refusal.message))
      case Right(tar) =>
        for
          inputs <- ReleaseInputs.fromMembers(tar.el)
          release <- EphemeryRelease.validate(inputs)
          _ <- known(release.iteration).filter(_ != release.genesisHashHex) match
            case Some(held) => Left(ReleaseRefusal.Republished(release.iteration, held, release.genesisHashHex))
            case None       => Right(())
        yield Fetched(release, stage, sha256(body.bytes), s"$root/releases/tag/$tag")
    // the reader empties the stage when it refuses; a release that parsed but does not validate leaves its files
    if result.isLeft then emptyStage(stage)
    result

  private def containsNow(release: ValidRelease): Boolean =
    val now = clock.instant()
    !now.isBefore(instant(release.genesisTimestamp)) && now.isBefore(instant(release.resetTime))

  private def instant(epochSecond: Long): Instant = Instant.ofEpochSecond(epochSecond)

  // ---- the request, with its retries --------------------------------------------------------------------------------

  /** One GET with up to [[Attempts]] attempts inside `overall`. A 404 that outlasts the retries is `notFound`. */
  private def get(
      url: URI,
      maxBytes: Long,
      accept: Option[String],
      overall: Instant,
      isApi: Boolean,
      notFound: => ReleaseRefusal
  ): Either[ReleaseRefusal, FetchedBody] =
    var attempt = 1
    var interrupted = false
    var outcome: Option[Either[TransportFailure, FetchedBody]] = None
    while outcome.isEmpty do
      val now = clock.instant()
      val deadline = earlier(overall, now.plus(budget.dividedBy(2)))
      transport.get(url, maxBytes, deadline, accept) match
        case ok @ Right(_) => outcome = Some(ok)
        case Left(failure) =>
          if Thread.currentThread().isInterrupted then
            interrupted = true
            outcome = Some(Left(failure))
          else if !retryable(failure, isApi) || attempt >= Attempts then outcome = Some(Left(failure))
          else
            val wait = Backoffs(attempt - 1)
            // the next attempt must still start inside the budget, with time to do something: a wait that would leave
            // less than MinAttempt is not taken
            if clock.instant().plus(wait).plus(MinAttempt).isBefore(overall) then
              sleeper.sleep(wait)
              if Thread.currentThread().isInterrupted then
                interrupted = true
                outcome = Some(Left(failure))
              else attempt += 1
            else outcome = Some(Left(failure))
    if interrupted then
      // the flag stays set for whoever interrupted: the process is being stopped
      Left(
        ReleaseRefusal.Unreachable(s"interrupted while fetching from ${Option(url.getHost).getOrElse("the source")}")
      )
    else outcome.get.left.map(f => refusalOf(f, url, attempt, isApi, notFound))

  private def refusalOf(
      failure: TransportFailure,
      url: URI,
      attempts: Int,
      isApi: Boolean,
      notFound: => ReleaseRefusal
  ): ReleaseRefusal =
    val host = Option(url.getHost).getOrElse("the source")
    failure match
      // the API's 404 is permanent (see `retryable`), so it is reported at once; an asset's only after the retries
      case TransportFailure.HttpStatus(404) => notFound
      case f if retryable(f, isApi) =>
        ReleaseRefusal.Unreachable(s"$host: ${f.message} (after $attempts attempt${if attempts == 1 then "" else "s"})")
      case f => ReleaseRefusal.BadSource(s"$host: ${f.message}")

  private def retryable(failure: TransportFailure, isApi: Boolean): Boolean = failure match
    case TransportFailure.Io(_) | TransportFailure.DeadlineExceeded => true
    case TransportFailure.HttpStatus(code) =>
      code >= 500 || code == 429 || (code == 404 && !isApi) || (code == 403 && isApi)
    case _ => false

  private def earlier(a: Instant, b: Instant): Instant = if a.isBefore(b) then a else b

  private def emptyStage(stage: Path): Unit =
    try
      val children = Files.list(stage)
      val all =
        try scala.jdk.CollectionConverters.IteratorHasAsScala(children.iterator()).asScala.toList
        finally children.close()
      all.foreach(StageSupport.deleteTree)
    catch case NonFatal(_) => ()

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

object ReleaseFetcher:

  /** A release ready to place. The caller owns `stage` (it holds the files to put in `testnet/`) and removes it. */
  final case class Fetched(release: ValidRelease, stage: Path, archiveSha256: String, sourceUrl: String)

  /** Attempts for one URL, the first included. */
  val Attempts: Int = 3

  /** The waits after the first and the second attempt. */
  val Backoffs: Vector[Duration] = Vector(Duration.ofSeconds(2), Duration.ofSeconds(4))

  /** A release archive is about 8 MB; the reader's own cap on the compressed stream is the same. */
  val MaxArchiveBytes: Long = TarLimits().maxCompressedBytes

  /** The least budget worth starting another attempt with. */
  val MinAttempt: Duration = Duration.ofSeconds(1)

  /** A release object of the GitHub API is a few KB. */
  val MaxApiBytes: Long = 1L << 20

  val ApiAccept: String = "application/vnd.github+json"

  /** The members `testnet/` does not get (R10): 18.9 MB the consensus client does not read, and encrypted keys. */
  val TarExcludes: Set[String] = Set("parsedBeaconState.json", "bootnode-keys/")

  private val TagName = Pattern.compile("ephemery-[0-9]{1,9}")

  /** The iteration whose window contains `now`, counted from the newest known one: `N + floor((now - genesis) /
    * interval)` (R7). Before the newest one's window opens it is the newest one itself.
    */
  def targetIteration(newest: ReleaseEntry, now: Instant): Int =
    val elapsed = now.getEpochSecond - newest.metadata.genesisTimestamp
    val interval = math.max(newest.metadata.resetInterval, 1L)
    if elapsed <= 0 then newest.iteration else newest.iteration + (elapsed / interval).toInt
