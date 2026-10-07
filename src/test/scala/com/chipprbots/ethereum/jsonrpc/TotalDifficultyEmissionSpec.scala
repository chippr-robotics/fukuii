package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.jsonrpc.EthBlocksJsonMethodsImplicits.given
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.*
import com.chipprbots.ethereum.utils.NetworkType

/** `totalDifficulty` is not a member of the execution-apis Block schema — it was dropped when the merge made it
  * meaningless for ETH-family chains. hive's rpc-compat compares the response object exactly, so emitting one extra key
  * fails the whole test: that single key was the ONLY diff in 10 of that suite's 40 failures (9 eth_getBlockByNumber
  * plus eth_getBlockByHash).
  *
  * ETC is proof-of-work and still reports it, so the field is gated on network type rather than deleted. This spec pins
  * BOTH directions, because a gate that is only tested in the direction it was written for is a gate that silently
  * becomes unconditional.
  */
class TotalDifficultyEmissionSpec extends AnyFlatSpec with Matchers:

  private val header = BlockHeader(
    parentHash = BlockHash(ByteString(Hex.decode("00" * 32))),
    ommersHash = BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
    beneficiary = ByteString(Hex.decode("00" * 20)),
    stateRoot = TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    transactionsRoot =
      TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    receiptsRoot = TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    logsBloom = BloomFilter(ByteString(Hex.decode("0" * 512))),
    difficulty = Difficulty(BigInt(131072)),
    number = BlockNumber(1),
    gasLimit = GasAmount(BigInt(8000000)),
    gasUsed = GasAmount.Zero,
    unixTimestamp = Timestamp(1701302272L),
    extraData = ByteString(Hex.decode("00")),
    mixHash = BlockHash(ByteString(Hex.decode("00" * 32))),
    nonce = ByteString(Hex.decode("0000000000000042"))
  )

  private val block = Block(header, BlockBody(Nil, Nil))
  private val weight = Some(ChainWeight.totalDifficultyOnly(BigInt(262144)))

  private def keysOf(response: BlockResponse): Set[String] =
    parse(compact(render(blockResponseEncoder.encodeJson(response)))).asInstanceOf[JObject].obj.map(_._1).toSet

  "the block encoder" should "omit totalDifficulty when the chain opts out (ETH family)" taggedAs (UnitTest) in {
    val response = BlockResponse(block, weight, fullTxs = false, emitTotalDifficulty = false)
    keysOf(response) should not contain "totalDifficulty"
  }

  it should "still emit every other field when totalDifficulty is omitted" taggedAs (UnitTest) in {
    // Guards against the gate accidentally dropping neighbouring keys: the entry sat between
    // `difficulty` and `extraData` in the base field list and was moved out of it.
    val keys = keysOf(BlockResponse(block, weight, fullTxs = false, emitTotalDifficulty = false))
    (keys should contain).allOf("number", "hash", "parentHash", "difficulty", "extraData", "stateRoot", "mixHash")
  }

  it should "emit totalDifficulty when the chain opts in (ETC)" taggedAs (UnitTest) in {
    val response = BlockResponse(block, weight, fullTxs = false, emitTotalDifficulty = true)
    keysOf(response) should contain("totalDifficulty")
    blockResponseEncoder.encodeJson(response) \ "totalDifficulty" shouldBe JString("0x40000")
  }

  it should "default to emitting it, so an unconverted call site cannot silently drop it" taggedAs (UnitTest) in {
    // The factory default is the ETC-safe one on purpose: any construction site not explicitly
    // converted keeps pre-merge behaviour rather than losing a field by omission.
    keysOf(BlockResponse(block, weight, fullTxs = false)) should contain("totalDifficulty")
    keysOf(BlockResponse(header, weight, pendingBlock = false)) should contain("totalDifficulty")
  }

  "the shipped chain configs" should "put ETC on the emitting side and the ETH family on the omitting side" taggedAs (UnitTest) in {
    // The service derives the flag as `networkType != NetworkType.ETH`. Pin the inputs that
    // decision reads, so a config edit that flips a chain's family fails here rather than
    // silently changing eth_getBlockByNumber output.
    blockchains.blockchains("etc").networkType shouldBe NetworkType.ETC
    blockchains.blockchains("mordor").networkType shouldBe NetworkType.ETC
    blockchains.blockchains("hive").networkType shouldBe NetworkType.ETH
    blockchains.blockchains("sepolia").networkType shouldBe NetworkType.ETH
  }
