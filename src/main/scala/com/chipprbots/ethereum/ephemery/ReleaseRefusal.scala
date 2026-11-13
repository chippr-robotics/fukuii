package com.chipprbots.ethereum.ephemery

/** Why a release (or an attempt to get one) is refused. Each case has an exit class (spec 028 contracts/exit-codes.md)
  * and a message that names the cause and the next step. The wording of the rule texts is `fetch-genesis.sh`'s.
  */
sealed trait ReleaseRefusal extends Product with Serializable:
  /** The process exit code of this refusal: 78 (`EX_CONFIG`, retrying does not help) or 69 (`EX_UNAVAILABLE`, a restart
    * may succeed once the release is out or the network is back).
    */
  def exitCode: Int
  def message: String

object ReleaseRefusal:

  /** `EX_UNAVAILABLE`: a later restart may succeed. */
  val ExitUnavailable: Int = 69

  /** `EX_CONFIG`: the operator must act. */
  val ExitConfig: Int = 78

  /** The release declares something this fukuii cannot represent (R3, R4, R5, R6, R8, R9, R10). */
  final case class CannotRepresent(feature: String, detail: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String = s"$detail; this fukuii cannot represent it ($feature), and a newer fukuii may be needed"

  /** The release disagrees with itself (R1, R2, R7, R11, N1, N2). */
  final case class Inconsistent(detail: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String = s"$detail; the release is inconsistent, so it is refused"

  /** The genesis block hash computed from the release is not the one it publishes (N3). */
  final case class HashMismatch(published: String, computed: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String =
      s"computed genesis $computed does not match the published $published; the release is refused"

  /** A release already held under the same name differs from the one now published. */
  final case class Republished(iteration: Int, cached: String, published: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String =
      s"ephemery-$iteration was published again with another genesis (held $cached, now $published); move the " +
        s"old releases/ephemery-$iteration and ephemery-$iteration directories aside to use the new one"

  /** The next iteration is not published yet. */
  final case class NotYetPublished(iteration: Int) extends ReleaseRefusal:
    val exitCode: Int = ExitUnavailable
    def message: String = s"ephemery-$iteration is not published yet; a restart retries"

  /** The release could not be fetched. The cause never carries a signed URL. */
  final case class Unreachable(cause: String) extends ReleaseRefusal:
    val exitCode: Int = ExitUnavailable
    def message: String = s"the release could not be fetched: $cause; a restart retries"

  /** The release's window does not contain now. */
  final case class WindowMismatch(iteration: Int, window: String, now: String) extends ReleaseRefusal:
    val exitCode: Int = ExitUnavailable
    def message: String = s"ephemery-$iteration covers $window, which does not contain now ($now)"

  /** The iteration ended and nothing newer can be had. */
  final case class Ended(iteration: Int, resetTime: String, why: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String = s"ephemery-$iteration ended at $resetTime and $why"

  /** The operator's source is not https, not a readable directory, or not a release. */
  final case class BadSource(detail: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String = s"$detail; the source is not a usable Ephemery release"

  /** The node's own Ephemery root cannot be written (a full disk, a permission): the operator must act. */
  final case class CannotStore(detail: String) extends ReleaseRefusal:
    val exitCode: Int = ExitConfig
    def message: String = s"cannot write the Ephemery root: $detail"
