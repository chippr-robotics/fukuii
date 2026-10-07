package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.*
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** EIP-7928 block-access-list store: the canonical RLP goes in under a block hash and comes back byte-for-byte, and an
  * absent block reads as None (a pre-Amsterdam or ETC block, or a pruned one).
  */
class BlockAccessListStorageSpec extends AnyFlatSpec with Matchers:

  private def address(last: Int): Address = Address(ByteString(Array.fill[Byte](19)(0) :+ last.toByte))

  private val sample: BlockAccessList = BlockAccessList(
    Seq(
      AccountChanges(
        address(1),
        storageChanges = Seq(SlotChanges(UInt256(2), Seq(StorageChange(1L, UInt256(9))))),
        storageReads = Seq(UInt256(3)),
        balanceChanges = Seq(BalanceChange(1L, UInt256(100))),
        nonceChanges = Seq(NonceChange(1L, BigInt(1))),
        codeChanges = Nil
      )
    )
  )

  "BlockAccessListStorage" should "round-trip a list's canonical RLP under its block hash" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val storage = new BlockAccessListStorage(EphemDataSource())
    val blockHash = ByteString(Array.fill[Byte](32)(0x11.toByte))
    storage.put(blockHash, sample.toBytes).commit()
    val stored = storage.get(blockHash)
    stored shouldBe Some(sample.toBytes)
    // The stored bytes are exactly what the header commits to, so they decode back to the same list.
    stored.map(BlockAccessList.decode) shouldBe Some(Right(sample))
  }

  it should "return None for a block with no stored access list" taggedAs (UnitTest, DatabaseTest) in {
    val storage = new BlockAccessListStorage(EphemDataSource())
    storage.get(ByteString(Array.fill[Byte](32)(0x22.toByte))) shouldBe None
  }

  it should "store the empty list as 0xc0" taggedAs (UnitTest, DatabaseTest) in {
    val storage = new BlockAccessListStorage(EphemDataSource())
    val blockHash = ByteString(Array.fill[Byte](32)(0x33.toByte))
    storage.put(blockHash, BlockAccessList.Empty.toBytes).commit()
    storage.get(blockHash) shouldBe Some(ByteString(0xc0.toByte))
  }
