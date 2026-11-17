package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.testing.Tags.UnitTest

class EvmCodeStorageSpec extends AnyFlatSpec with Matchers:

  private val code: ByteString = ByteString(Array[Byte](0x60, 0x01, 0x00))
  private val hash: ByteString = kec256(code)

  behavior.of("EvmCodeStorage's execution reads")

  // H3, review beacon: what execution read under a code hash was run, whatever the bytes were. Bytes that do not hash
  // to their key are not that code: absent, and never cached, so the importer's write of the code is what runs next.
  it should "read bytes that do not hash to their key as absent, and not cache them (H3)" taggedAs UnitTest in {
    val storage = new EvmCodeStorage(EphemDataSource())
    storage.put(hash, ByteString(Array[Byte](0x60, 0x00))).commit()

    storage.getForExecution(hash) shouldBe None
    storage.getSizeForExecution(hash) shouldBe None
    storage.prefetchForExecution(hash, _ => true) shouldBe EvmCodeStorage.PrefetchOutcome.Absent

    storage.put(hash, code).commit() // the importer's write
    storage.getForExecution(hash) shouldBe Some(code)
    storage.getSizeForExecution(hash) shouldBe Some(code.length)
  }
