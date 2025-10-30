package com.chipprbots.ethereum

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.BigIntegers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.ByteArrayEncoder
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.HashByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp.RLPImplicits.{given, _}
import com.chipprbots.ethereum.utils.ByteUtils

package object domain {
  type HeadersSeq = Seq[BlockHeader]

  object EthereumUInt256Mpt {
    val byteArrayBigIntSerializer: ByteArrayEncoder[BigInt] = new ByteArrayEncoder[BigInt] {
      override def toBytes(input: BigInt): Array[Byte] =
        ByteUtils.padLeft(ByteString(BigIntegers.asUnsignedByteArray(input.bigInteger)), 32).toArray[Byte]
    }

    val rlpBigIntSerializer: ByteArraySerializable[BigInt] = new ByteArraySerializable[BigInt] {
      override def fromBytes(bytes: Array[Byte]): BigInt = rlp.decode[BigInt](bytes)

      override def toBytes(input: BigInt): Array[Byte] = rlp.encode[BigInt](input)
    }

    def storageMpt(rootHash: ByteString, nodeStorage: MptStorage): MerklePatriciaTrie[BigInt, BigInt] =
      MerklePatriciaTrie[BigInt, BigInt](rootHash.toArray[Byte], nodeStorage)(
        HashByteArraySerializable(byteArrayBigIntSerializer),
        rlpBigIntSerializer
      )
  }

  object ArbitraryIntegerMpt {
    val bigIntSerializer: ByteArraySerializable[BigInt] = new ByteArraySerializable[BigInt] {
      override def fromBytes(bytes: Array[Byte]): BigInt = BigInt(bytes)
      override def toBytes(input: BigInt): Array[Byte] = input.toByteArray
    }

    def storageMpt(rootHash: ByteString, nodeStorage: MptStorage): MerklePatriciaTrie[BigInt, BigInt] =
      MerklePatriciaTrie[BigInt, BigInt](rootHash.toArray[Byte], nodeStorage)(
        HashByteArraySerializable(bigIntSerializer),
        bigIntSerializer
      )
  }

}
