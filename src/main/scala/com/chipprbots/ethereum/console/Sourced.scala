package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** What the TUI knows about one polled source (the peer manager, the sync controller), and how fresh it is (spec 027
  * FR-011, research §7.1). Each source carries its own freshness, so a slow source degrades only its own panel.
  */
enum Sourced[+A]:
  /** The source answered the latest poll, at `at`. */
  case Fresh(answer: A, at: Instant)

  /** The source missed the latest poll's wait; `last` is what it said last, at `since`. */
  case Stale(last: A, since: Instant)

  /** The source never answered, failed, or has been silent for longer than [[Sourced.UnresponsiveAfter]]. */
  case Unresponsive(reason: String)

  /** The value to show: the latest answer, fresh or stale. */
  def value: Option[A] = this match
    case Fresh(v, _) => Some(v)
    case Stale(v, _) => Some(v)
    case _           => None

object Sourced:

  /** What one poll of a source got. */
  enum Poll[+A]:
    /** The source answered within the poll's wait. */
    case Answered(value: A)

    /** The wait ended before the source answered. */
    case Missed

    /** The source failed, other than by being slow. */
    case Failed(reason: String)

  /** How long a source that answered before may miss polls and still show its last answer, as Stale. */
  val UnresponsiveAfter: Duration = Duration.ofSeconds(5)

  /** The freshness after a poll: Fresh on an answer; Stale on a miss by a source that answered within
    * `unresponsiveAfter`; Unresponsive on a failure, on a miss by a source that never answered, or once a stale
    * source has been silent for longer than `unresponsiveAfter`.
    *
    * @param missed
    *   the reason a source that never answered gives, for example "no reply within 500ms"
    */
  def next[A](
      previous: Option[Sourced[A]],
      poll: Poll[A],
      now: Instant,
      missed: => String,
      unresponsiveAfter: Duration = UnresponsiveAfter
  ): Sourced[A] =
    def staleOrSilent(last: A, since: Instant): Sourced[A] =
      if Duration.between(since, now).compareTo(unresponsiveAfter) <= 0 then Stale(last, since)
      else Unresponsive(s"no reply for ${Duration.between(since, now).toSeconds}s")
    poll match
      case Poll.Answered(value) => Fresh(value, now)
      case Poll.Failed(reason)  => Unresponsive(reason)
      case Poll.Missed =>
        previous match
          case Some(Fresh(last, at))     => staleOrSilent(last, at)
          case Some(Stale(last, since))  => staleOrSilent(last, since)
          case Some(Unresponsive(_)) | None => Unresponsive(missed)
