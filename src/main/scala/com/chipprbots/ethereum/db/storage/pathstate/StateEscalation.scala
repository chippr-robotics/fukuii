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

/** The branches a proof-of-work node gave its state up for (`state.escalation.given-up`; the user's decision of
  * 2026-11-15): each named by its first block above the block it leaves the chain at, with its height. A branch that is
  * here and still cannot be followed after the sync that its escalation began is never escalated for again: the node
  * stays on the chain it has, knowingly, until an operator removes the entry.
  *
  * A branch is known again by '''any block of it that is, or stands on, a block named here''' (forge): what the number
  * index says of the chain changes with the sync that follows an escalation, and a name worked out from it again would
  * not be the same. The height is what ends that walk: nothing below the lowest height named here is on a branch that
  * was given up.
  *
  * Written in one place: the synced batch that begins the clear, with the escalation it belongs to. It stays through
  * every clear and every sync ('''it is not among what a clear removes''', and must not be put there). One entry for an
  * escalation, and no more than one escalation in a cool-down: it does not grow to matter.
  */
final case class GivenUpBranch(number: BigInt, hash: ByteString)

object GivenUpBranches:
  val Key: String = "state.escalation.given-up"

  private val Version = "v1"
  private val HashHexLength = 64

  def encode(branches: Set[GivenUpBranch]): String =
    val listed = branches.toList
      .sortBy(branch => (branch.number, Hex.toHexString(branch.hash.toArray)))
      .map(branch => s"${branch.number}:${Hex.toHexString(branch.hash.toArray)}")
    s"$Version:${listed.mkString(",")}"

  /** The branches a stored value names. A value this build cannot read throws: whoever asks takes it for "every branch
    * was given up", never for none, since "none" would let a node clear its state for the same branch again and again.
    */
  def decode(value: String): Set[GivenUpBranch] =
    def malformed: Nothing = throw new IllegalStateException(s"$Key is not a $Version list of branches: '$value'")
    if !value.startsWith(s"$Version:") then malformed
    val listed = value.drop(Version.length + 1)
    if listed.isEmpty then Set.empty
    else
      listed
        .split(",", -1)
        .iterator
        .map(_.split(':') match
          case Array(number, hash) if number.nonEmpty && number.forall(_.isDigit) && hash.length == HashHexLength =>
            GivenUpBranch(BigInt(number), Try(ByteString(Hex.decode(hash))).getOrElse(malformed))
          case _ => malformed
        )
        .toSet

/** The branch the sync after a proof-of-work escalation is to take its pivot on (`state.escalation.pivot-on`; B-307,
  * forge): the first block of the refused branch above the block it leaves the node's chain at. The pivot of a sync is
  * chosen by height, and on a chain that chooses by weight the heavier chain can be the shorter: a pivot on the chain
  * that was left would have the node sync that chain again, meet the branch again, and drop it for good.
  *
  * Written with the clear of the escalation; removed when a sync hands off, by a clear that is for no branch, and when
  * the requirement is dropped: after [[PivotOnBranch.MaxAttempts]] syncs whose headers were of another chain, no peer
  * serves the branch any more (the user's decision of 2026-11-16: drop it then, and say so at ERROR).
  *
  * @param attempts
  *   how many syncs reached this height on another chain
  * @param firstMissMillis
  *   when no peer on the branch was first found, where peers are asked (the bound in time)
  */
final case class PivotOnBranch(
    number: BigInt,
    hash: ByteString,
    attempts: Int = 0,
    firstMissMillis: Option[Long] = None
)

object PivotOnBranch:
  val Key: String = "state.escalation.pivot-on"

  /** How many syncs may find another chain at the branch's height before the requirement is dropped. */
  val MaxAttempts: Int = 3

  private val HashHexLength = 64

  def encode(pivot: PivotOnBranch): String =
    s"v1:${pivot.number}:${Hex.toHexString(pivot.hash.toArray)}:${pivot.attempts}:" +
      pivot.firstMissMillis.fold("-")(_.toString)

  /** The requirement a stored value names; one this build cannot read throws, and whoever asks drops the requirement
    * and says so: a sync is not held for ever on a record no one can read.
    */
  def decode(value: String): PivotOnBranch =
    def malformed: Nothing = throw new IllegalStateException(s"$Key is not a v1 requirement: '$value'")
    def digits(text: String): Boolean = text.nonEmpty && text.forall(_.isDigit)
    value.split(':') match
      case Array("v1", number, hash, attempts, first)
          if digits(number) && digits(attempts) && hash.length == HashHexLength && (first == "-" || digits(first)) =>
        PivotOnBranch(
          BigInt(number),
          Try(ByteString(Hex.decode(hash))).getOrElse(malformed),
          Try(attempts.toInt).getOrElse(malformed),
          Option.when(first != "-")(Try(first.toLong).getOrElse(malformed))
        )
      case _ => malformed

/** The heights whose entries in the index of blocks by number are still to be deleted after a clear for an escalation
  * (`state.reset-trim-above`; B-303): above `commonBlock`, up to `upper`. Put in the synced batch that begins the
  * clear, removed, synced, with [[StateResetPending]], once the entries are deleted. A node started with it set deletes
  * them again before anything syncs.
  *
  * The chain the node was on above the block it has in common with the chain it is to follow is the chain it left: left
  * in the index, a sync takes its headers for stored, and a height answers with a block of it.
  *
  * @param commonBlock
  *   the highest block the two chains are known to share: every entry above it goes
  * @param upper
  *   the highest height that may have an entry
  */
final case class StateResetTrim(commonBlock: BigInt, upper: BigInt)

object StateResetTrim:
  val Key: String = "state.reset-trim-above"

  def encode(trim: StateResetTrim): String = s"v1:${trim.commonBlock}:${trim.upper}"

  /** The trim a stored value names; one this build cannot read throws, and the node does not start over it. */
  def decode(value: String): StateResetTrim =
    def digits(text: String): Boolean = text.nonEmpty && text.forall(_.isDigit)
    value.split(':') match
      case Array("v1", common, upper) if digits(common) && digits(upper) =>
        StateResetTrim(BigInt(common), BigInt(upper))
      case _ =>
        throw new IllegalStateException(
          s"$Key is not a v1 trim: '$value'. It says which entries of the index of blocks by number a clear of the " +
            "state still has to delete, and the node does not start without knowing. Take a copy of the data " +
            "directory; then either restore it from a backup, or move its rocksdb directory away to sync again."
        )

/** The mark that a clear of the state was begun and its rows may not all be gone yet (`state.reset-pending`): put in
  * the synced batch that begins the clear, removed, synced, once the rows are deleted. A node started with it set
  * deletes the rows again before anything reads them.
  */
object StateResetPending:
  val Key: String = "state.reset-pending"
  def encode(atMillis: Long): String = s"v1:$atMillis"
