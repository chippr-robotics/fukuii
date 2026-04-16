package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

/** CL-provided fork choice state, as per the Engine API spec (engine_forkchoiceUpdated).
  *
  * @param headBlockHash
  *   block hash of the head of the canonical chain
  * @param safeBlockHash
  *   block hash of the most recent "safe" block (2/3 attested)
  * @param finalizedBlockHash
  *   block hash of the most recent finalized block
  */
case class ForkChoiceState(
    headBlockHash: ByteString,
    safeBlockHash: ByteString,
    finalizedBlockHash: ByteString
)
