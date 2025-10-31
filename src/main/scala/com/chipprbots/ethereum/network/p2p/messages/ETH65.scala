package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp._

/** ETH65 protocol messages - adds transaction pool support
  * See https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth65
  */
object ETH65 {

  object NewPooledTransactionHashes {
    implicit class NewPooledTransactionHashesEnc(val underlyingMsg: NewPooledTransactionHashes)
        extends MessageSerializableImplicit[NewPooledTransactionHashes](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.NewPooledTransactionHashesCode

      override def toRLPEncodable: RLPEncodeable = toRlpList(msg.txHashes)
    }

    implicit class NewPooledTransactionHashesDec(val bytes: Array[Byte]) extends AnyVal {
      def toNewPooledTransactionHashes: NewPooledTransactionHashes = rawDecode(bytes) match {
        case rlpList: RLPList => NewPooledTransactionHashes(fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode NewPooledTransactionHashes")
      }
    }
  }

  case class NewPooledTransactionHashes(txHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.NewPooledTransactionHashesCode

    override def toString: String =
      s"NewPooledTransactionHashes { " +
        s"txHashes: ${txHashes.map(h => Hex.toHexString(h.toArray[Byte])).mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"NewPooledTransactionHashes { count: ${txHashes.size} }"
  }

  object GetPooledTransactions {
    implicit class GetPooledTransactionsEnc(val underlyingMsg: GetPooledTransactions)
        extends MessageSerializableImplicit[GetPooledTransactions](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetPooledTransactionsCode

      override def toRLPEncodable: RLPEncodeable = toRlpList(msg.txHashes)
    }

    implicit class GetPooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetPooledTransactions: GetPooledTransactions = rawDecode(bytes) match {
        case rlpList: RLPList => GetPooledTransactions(fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetPooledTransactions")
      }
    }
  }

  case class GetPooledTransactions(txHashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetPooledTransactionsCode

    override def toString: String =
      s"GetPooledTransactions { " +
        s"txHashes: ${txHashes.map(h => Hex.toHexString(h.toArray[Byte])).mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"GetPooledTransactions { count: ${txHashes.size} }"
  }

  object PooledTransactions {
    implicit class PooledTransactionsEnc(val underlyingMsg: PooledTransactions)
        extends MessageSerializableImplicit[PooledTransactions](underlyingMsg)
        with RLPSerializable {
      import BaseETH6XMessages.SignedTransactions._

      override def code: Int = Codes.PooledTransactionsCode

      override def toRLPEncodable: RLPEncodeable = RLPList(msg.txs.map(_.toRLPEncodable): _*)
    }

    implicit class PooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      import BaseETH6XMessages.SignedTransactions._
      import BaseETH6XMessages.TypedTransaction._

      def toPooledTransactions: PooledTransactions = rawDecode(bytes) match {
        case rlpList: RLPList =>
          PooledTransactions(rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction))
        case _ => throw new RuntimeException("Cannot decode PooledTransactions")
      }
    }
  }

  case class PooledTransactions(txs: Seq[SignedTransaction]) extends Message {
    override def code: Int = Codes.PooledTransactionsCode

    override def toString: String =
      s"PooledTransactions { " +
        s"txs: ${txs.mkString(", ")} " +
        s"}"

    override def toShortString: String =
      s"PooledTransactions { count: ${txs.size} }"
  }
}
