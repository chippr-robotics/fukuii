package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader

/** Decides what to do with the body stored (or not) under a header that the node anchors without having fetched the
  * body through a validating path — the SNAP pivot at finalization, and the same pivot (or genesis) on start-up repair.
  *
  * CQ-020-07: SNAP finalization used to write `Block(pivotHeader, BlockBody.empty)` to get the number→hash mapping.
  * That overwrote the body a `newPayload` had already stored (Ephemery block 137,594: 16 withdrawals lost, 639 bytes
  * served where every other client serves 1,154), and since ChainDownloader only fetches a body that is ABSENT, nothing
  * ever replaced it. A body must therefore never be invented: it is kept when it validates, derived when the header
  * commits to an empty one, and otherwise left absent (or removed) so the backfill fetches it from a peer and validates
  * it.
  *
  * Pure: callers do the writes.
  */
object StoredBodyCheck:

  enum Action:
    /** The stored body belongs to the header. Leave it. */
    case Keep

    /** Store `body`, derived from the header (it commits to an empty body). `replacing` names what was wrong with the
      * stored one, if there was one.
      */
    case Write(body: BlockBody, replacing: Option[String])

    /** No body is stored and none can be derived: leave it absent for the backfill to fetch. */
    case LeaveAbsent

    /** The stored body contradicts the header and the right one cannot be derived: remove it so it is fetched. */
    case Remove(reason: String)

  /** Full check of `body` against `header`: everything `StdBlockValidator.validateHeaderAndBody` checks (transactions
    * root, ommers hash, withdrawals root, RLP size, blob gas) plus the withdrawals-field presence the validator leaves
    * to RLP decoding (a Shanghai header with `withdrawals = None` passes the validator, because an absent list hashes
    * like an empty one, but encodes as a 3-item body). One block's worth of trie work: for anchors, not for hot paths.
    */
  def mismatch(header: BlockHeader, body: BlockBody): Option[String] =
    BlockBody
      .shapeMismatch(header, body)
      .orElse(StdBlockValidator.validateHeaderAndBody(header, body).left.toOption.map(_.toString))

  def decide(header: BlockHeader, stored: Option[BlockBody]): Action =
    stored match
      case Some(body) =>
        mismatch(header, body) match
          case None => Action.Keep
          case Some(reason) =>
            BlockBody.committedEmpty(header) match
              case Some(derived) => Action.Write(derived, replacing = Some(reason))
              case None          => Action.Remove(reason)
      case None =>
        BlockBody.committedEmpty(header) match
          case Some(derived) => Action.Write(derived, replacing = None)
          case None          => Action.LeaveAbsent
