package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions._
import com.chipprbots.ethereum.rlp

object RawTransactionCodec {

  def asRawTransaction(e: SignedTransaction): ByteString =
    ByteString(rlp.encode(e.toRLPEncodable))
}
