package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.ethereum.utils.Hex

/** Why a node gave up the state it held and synced it again from scratch (spec 018 C5a; the user's decision of
  * 2026-11-15: two kinds, told coarsely).
  */
enum EscalationKind(val text: String):
  /** The chain the node is to follow cannot be reached from the state it holds: a branch that leaves its chain below
    * what the changeset records reach, or below the block its state began at.
    */
  case Unreachable extends EscalationKind("unreachable")

  /** The state it holds is at fault: records that are not what they should be, or a block it executed and accepted
    * before that fails now.
    */
  case LocalFault extends EscalationKind("local")

/** The last time this datadir's state was cleared for a sync from scratch (`state.escalation.last`): when, why, and for
  * which head. It is what the cool-down between two such clears is counted from, across restarts, and what tells a
  * branch that was already given up for from a new one.
  *
  * Written in one place: the synced batch that begins the clear ([[PathStateStorage.clearForResync]]). It stays through
  * the clear and through the sync that follows.
  *
  * @param atMillis
  *   the wall clock when the clear began: it must mean the same after a restart
  * @param headHash
  *   the head that could not be followed, or the first block of the branch that could not be reached
  */
final case class StateEscalation(atMillis: Long, kind: EscalationKind, headHash: ByteString, headNumber: BigInt)

object StateEscalation:
  val Key: String = "state.escalation.last"

  private val Version = "v1"
  private val HashHexLength = 64

  def encode(escalation: StateEscalation): String =
    s"$Version:${escalation.atMillis}:${escalation.kind.text}:" +
      s"${Hex.toHexString(escalation.headHash.toArray)}:${escalation.headNumber}"

  /** The escalation a stored value names. A value this build cannot read throws: whoever counts the cool-down from it
    * takes an unreadable one for an escalation that has just happened, never for none, since "none" would let a node
    * clear its state again at once.
    */
  def decode(value: String): StateEscalation =
    def malformed: Nothing = throw new IllegalStateException(s"$Key is not a $Version escalation: '$value'")
    def digits(text: String): Boolean = text.nonEmpty && text.forall(_.isDigit)
    value.split(':') match
      case Array(Version, at, kind, hash, number) if digits(at) && digits(number) && hash.length == HashHexLength =>
        StateEscalation(
          Try(at.toLong).getOrElse(malformed),
          EscalationKind.values.find(_.text == kind).getOrElse(malformed),
          Try(ByteString(Hex.decode(hash))).getOrElse(malformed),
          BigInt(number)
        )
      case _ => malformed

/** The mark that a clear of the state was begun and its rows may not all be gone yet (`state.reset-pending`): put in
  * the synced batch that begins the clear, removed, synced, once the rows are deleted. A node started with it set
  * deletes the rows again before anything reads them.
  */
object StateResetPending:
  val Key: String = "state.reset-pending"
  def encode(atMillis: Long): String = s"v1:$atMillis"
