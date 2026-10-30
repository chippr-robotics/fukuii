package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** Spec 022 plan D4, round 2 review A: how long each thing holding healing up has waited.
  *
  * Time here is idle minutes: minutes in which no trie fetch could help. The coordinator's tick says when one has
  * passed. Re-pegs, the heal after them and walks neither stop the time nor reset it, because on a live chain a re-peg
  * comes every 14 to 26 minutes. Nor does anything else healing is doing: only a blocker's own end does.
  *
  * '''Code.''' A code hash ages from when it was first seen waiting, and goes on ageing while nothing waits on it (a
  * re-peg dropped the leaf that did; the same leaf comes back). It stops being tracked when its code is stored. Another
  * hash's code arriving changes nothing here, so a stuck hash keeps its age once a quickly served one has gone, and a
  * new hash starts at nought whatever an older one has waited.
  *
  * '''Anything else.''' One clock for all of it, keyed by what the excluded set holds ([[SetSignature]]): it restarts
  * when the set's accounts change, and ends when nothing but code blocks.
  *
  * '''Memory.''' The map holds the hashes blocking now, plus at most `maxOrphanedCode` that blocked earlier and are not
  * stored yet, eldest dropped first. A dropped hash that blocks again only starts its wait again, which delays a
  * hand-off and never hurries one.
  *
  * '''Restart.''' Not durable. A supervisor restart or a node restart starts every clock again: another 30 idle
  * minutes, and the durable set still holds completion meanwhile.
  *
  * Actor-confined: not thread-safe.
  */
final class BlockerClock(maxOrphanedCode: Int = BlockerClock.DefaultMaxOrphanedCode):
  import BlockerClock.*

  private var idleMinutes: Long = 0L
  private val codeSince = mutable.LinkedHashMap.empty[ByteString, Long]
  private var otherSince: Option[(SetSignature, Long)] = None

  /** `blocking` is the code healing waits on now. A hash not seen before starts its wait here. */
  def observeCode(blocking: collection.Set[ByteString]): Unit =
    blocking.foreach(hash => if !codeSince.contains(hash) then codeSince(hash) = idleMinutes)
    val orphaned = codeSince.size - blocking.size
    if orphaned > maxOrphanedCode then
      codeSince.keysIterator.filterNot(blocking).take(orphaned - maxOrphanedCode).toVector.foreach(codeSince.remove)

  /** The code `hash` has been written. */
  def codeStored(hash: ByteString): Unit = codeSince.remove(hash): Unit

  /** The hashes being tracked: what a tick checks for an acknowledgement that was lost. */
  def trackedCode: collection.Set[ByteString] = codeSince.keySet

  /** Whether every hash of `blocking`, and there is one, has waited at least `minutes`. */
  def codeHasWaited(blocking: Iterable[ByteString], minutes: Int): Boolean =
    blocking.nonEmpty && blocking.forall(hash => codeSince.get(hash).exists(idleMinutes - _ >= minutes))

  /** Something other than code blocks, with the set as `signature` says. */
  def otherBlocking(signature: SetSignature): Unit =
    if !otherSince.exists(_._1 == signature) then otherSince = Some((signature, idleMinutes))

  /** Nothing other than code blocks. */
  def otherGone(): Unit = otherSince = None

  def otherHasWaited(minutes: Int): Boolean = otherSince.exists { case (_, since) => idleMinutes - since >= minutes }

  /** A minute has passed in which no trie fetch could help. */
  def idleMinutePassed(): Unit = idleMinutes += 1

object BlockerClock:
  val DefaultMaxOrphanedCode: Int = 4096

  /** Which accounts the excluded set holds, dormant or not, whatever the root: their count and the XOR of the first
    * eight bytes of each (account hashes, so evenly spread). Going dormant and coming back does not change it; an entry
    * leaving or arriving does. A collision only lets a clock run on.
    */
  final case class SetSignature(accounts: Long, mix: Long):
    def and(account: ByteString): SetSignature =
      SetSignature(accounts + 1, mix ^ java.nio.ByteBuffer.wrap(account.take(8).padTo(8, 0.toByte).toArray).getLong)

  object SetSignature:
    val Empty: SetSignature = SetSignature(0L, 0L)
