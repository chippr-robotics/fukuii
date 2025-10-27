package com.chipprbots.ethereum.consensus.pow

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable

package object blocks {

  /** This is type `X` in `BlockGenerator`.
    *
    * @see
    *   [[com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator EthashBlockGenerator]],
    *   [[com.chipprbots.ethereum.consensus.blocks.BlockGenerator.X BlockGenerator{ type X}]]
    */
  final type Ommers = Seq[BlockHeader]

  implicit class OmmersSeqEnc(blockHeaders: Seq[BlockHeader]) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = RLPList(blockHeaders.map(_.toRLPEncodable): _*)
  }
}
