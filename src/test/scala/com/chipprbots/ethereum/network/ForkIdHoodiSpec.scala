package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.*

/** ForkId rows for Hoodi (spec 028 S1, FR-019).
  *
  * Ground truth: go-ethereum `core/forkid/forkid_test.go`, the "Hoodi test cases" block (fetched 2026-11-12), also
  * re-derived with crc32 over the genesis hash and the big-endian fork timestamps. Shanghai and Cancun are at 0, before
  * the genesis timestamp 1742212800, so EIP-6122 drops them from the fork list (`ForkIdGenesisTimestampSpec`); the
  * first checksum is therefore CRC32(genesis hash) = 0xbef71d30 with next = Prague.
  */
class ForkIdHoodiSpec extends AnyFlatSpec with Matchers:

  private val hoodiConf = blockchains.blockchains("hoodi")
  private val HoodiGenesisTimestamp = 1742212800L
  private val hoodiGenesisHash =
    ByteString(Hex.decode("bbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"))

  private def create(block: BigInt, ts: Long): ForkId =
    ForkId.create(hoodiGenesisHash, HoodiGenesisTimestamp, hoodiConf)(block, ts)

  "ForkId for Hoodi" should "match go-ethereum's five rows" taggedAs (UnitTest, NetworkTest) in {
    create(0, 0) shouldBe ForkId(0xbef71d30L, Some(1742999832L))
    create(123, 1742999831L) shouldBe ForkId(0xbef71d30L, Some(1742999832L)) // last Cancun
    create(123, 1742999832L) shouldBe ForkId(0x0929e24eL, Some(1761677592L)) // first Prague
    create(123, 1761677591L) shouldBe ForkId(0x0929e24eL, Some(1761677592L)) // last Prague
    create(123, 1761677592L) shouldBe ForkId(0xe7e0e7ffL, Some(1762365720L)) // first Osaka
    create(123, 1762365719L) shouldBe ForkId(0xe7e0e7ffL, Some(1762365720L)) // last Osaka
    create(123, 1762365720L) shouldBe ForkId(0x3893353eL, Some(1762955544L)) // first BPO1
    create(123, 1762955543L) shouldBe ForkId(0x3893353eL, Some(1762955544L)) // last BPO1
    create(123, 1762955544L) shouldBe ForkId(0x23aa1351L, None) // first BPO2
    create(123, 2000000000L) shouldBe ForkId(0x23aa1351L, None) // future
  }
