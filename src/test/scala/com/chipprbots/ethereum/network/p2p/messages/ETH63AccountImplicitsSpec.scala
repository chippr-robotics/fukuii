package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits._
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.{RLPList, RLPValue}
import com.chipprbots.ethereum.testing.Tags._

class ETH63AccountImplicitsSpec extends AnyFlatSpec with Matchers {

  "ETH63.AccountImplicits.AccountDec" should "normalize empty storageRoot/codeHash to canonical empty hashes" taggedAs (UnitTest) in {
    // RLP([nonce, balance, storageRoot, codeHash])
    // Here storageRoot/codeHash are encoded as empty byte strings by some peers.
    val encoded = rlp.encode(
      RLPList(
        RLPValue(Array.emptyByteArray),
        RLPValue(Array.emptyByteArray),
        RLPValue(Array.emptyByteArray),
        RLPValue(Array.emptyByteArray)
      )
    )

    val decoded = encoded.toAccount

    decoded.nonce shouldBe UInt256.Zero
    decoded.balance shouldBe UInt256.Zero
    decoded.storageRoot shouldBe Account.EmptyStorageRootHash
    decoded.codeHash shouldBe Account.EmptyCodeHash
  }

  it should "preserve non-empty storageRoot/codeHash values" taggedAs (UnitTest) in {
    val customStorageRoot = ByteString(Array.fill[Byte](32)(0x11.toByte))
    val customCodeHash = ByteString(Array.fill[Byte](32)(0x22.toByte))

    val encoded = rlp.encode(
      RLPList(
        RLPValue(Array.emptyByteArray),
        RLPValue(Array.emptyByteArray),
        RLPValue(customStorageRoot.toArray),
        RLPValue(customCodeHash.toArray)
      )
    )

    val decoded = encoded.toAccount

    decoded.storageRoot shouldBe customStorageRoot
    decoded.codeHash shouldBe customCodeHash
  }
}
