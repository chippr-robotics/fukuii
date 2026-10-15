package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import scala.io.Source

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** execution-apis #881 (377333f4de), measured: `eth_simulateV1` on the Amsterdam rpc-compat chain answers the published
  * vectors byte for byte — the simulated block's `blockAccessListHash` (EIP-7928, built from the simulated execution),
  * its `slotNumber` (the parent's + 1), the EIP-8037 header `gasUsed`, the EIP-7708 logs in place of `traceTransfers`'
  * synthetic ones, the uncapped GAS of a call without validation, and the nonce-limit error.
  *
  * The node is the rpc-compat chain's head, block 0x3c (slot 3), rebuilt from `rpc-compat/amsterdam/head-0x3c.json`:
  * the head header as fukuii imported it and the trie nodes on every path the vectors read or write (taken with
  * eth_getProof from a fukuii node that imported `tests/chain.rlp`). The vectors are execution-apis'
  * `tests/eth_simulateV1` `.io` files, copied verbatim; hive's rpc-compat simulator runs the same requests and compares
  * the same responses, error messages excepted, as here.
  */
// scalastyle:off magic.number
class EthSimulateRpcCompatAmsterdamSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Resources = "rpc-compat/amsterdam/"

  private def resource(name: String): String =
    val source = Source.fromResource(Resources + name)
    try source.mkString
    finally source.close()

  private def hex(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))

  /** The rpc-compat chain's config (execution-apis `tests/forkenv.json`): every block fork long behind block 0x3c, the
    * timestamp forks as declared, Amsterdam at 570. The chain id enters every simulated transaction's hash.
    */
  private val rpcCompatConfig: BlockchainConfig = amsterdamConfig.copy(
    chainId = ChainId(BigInt("3503995874084926")),
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(390L),
      cancunTimestamp = Some(420L),
      pragueTimestamp = Some(450L),
      osakaTimestamp = Some(480L),
      amsterdamTimestamp = Some(570L),
      bpo1Timestamp = Some(510L),
      bpo2Timestamp = Some(540L)
    ),
    depositContractAddress = Some(Address(0))
  )

  /** A node whose best block is the rpc-compat head, with the state the vectors touch. */
  private class Node extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = rpcCompatConfig

    private val fixture: JValue = parse(resource("head-0x3c.json"))

    for case JString(node) <- (fixture \ "nodes").children do
      val bytes = hex(node)
      storagesInstance.storages.stateStorage.saveNode(ByteString(kec256(bytes)), bytes, 0)
    for case (_, JString(code)) <- (fixture \ "codes").asInstanceOf[JObject].obj do
      val bytes = ByteString(hex(code))
      storagesInstance.storages.evmCodeStorage.put(ByteString(kec256(bytes.toArray)), bytes).commit()

    val head: BlockHeader = hex((fixture \ "header").asInstanceOf[JString].s).toBlockHeader
    blockchainWriter.save(Block(head, BlockBody(Nil, Nil, Some(Nil))), Nil, ChainWeight.zero, saveAsBestBlock = true)

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      rpcCompatConfig
    )

    def simulate(
        request: EthSimulateService.EthSimulateRequest
    ): Either[JsonRpcError, EthSimulateService.EthSimulateResponse] =
      service.ethSimulate(request).unsafeRunSync()

  /** The vector's request and expected response, as hive's rpc-compat reads them (`>>` and `<<` lines). */
  private def vector(name: String): (JValue, JValue) =
    val lines = resource(name + ".io").linesIterator.toSeq
    def line(prefix: String) = parse(lines.find(_.startsWith(prefix)).get.stripPrefix(prefix).trim)
    (line(">>"), line("<<"))

  /** hive's rpc-compat removes error messages before it compares; so does this. */
  private def withoutMessages(json: JValue): JValue = json.removeField {
    case ("message", _) => true
    case _              => false
  }

  private def run(node: Node, request: JValue): JValue =
    val params = (request \ "params").asInstanceOf[JArray]
    val response = for
      req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(Some(params))
      res <- node.simulate(req)
    yield EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(res)
    response match
      case Right(result) => JObject("result" -> result)
      case Left(err) =>
        JObject("error" -> JObject(("code" -> JInt(err.code)) :: err.data.map("data" -> _).toList))

  private lazy val node = new Node

  "the rebuilt rpc-compat head" should "be block 0x3c with slot 3, as the vectors build on" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    node.head.number shouldBe BlockNumber(0x3c)
    node.head.slotNumber shouldBe Some(BigInt(3))
    node.head.hash.value shouldBe ByteString(hex("0x52bccfc9c4d4874b1898c0fbd0ae3b598a0edc7b94d9b796857dbbe1093c32e0"))
  }

  private val vectors = Seq(
    // One empty block: the access list holds the system calls only (index 0 and n + 1), slot 3 + 1.
    "ethSimulate-empty" -> "0x3df8c6b6b03a905b7559903822af0baa09ff2d9aaf8f01a72fe7e4c3628f4e51",
    // Two transfers to new accounts: header gasUsed = max(execution, state) = 2 x 183,600 while receipts sum.
    "ethSimulate-simple" -> "0xb9f8090c47c2d7ba3b222ffcdd9b88d9b5b18ea12c385b010d53b63ce233704d",
    // traceTransfers in an EIP-7708 block: only the protocol's 0xff..fe log, no synthetic 0xee..ee one.
    "ethSimulate-eth-send-should-produce-logs" -> "0xb9ebec0436074d9472c1f280e6131d8599afcc64c6e9e6f07fedd65e2588b987",
    // GAS without validation is the whole gas limit less what was spent, not the 2^24 execution cap.
    "ethSimulate-get-block-properties" -> "0x8dfd2ba404c34b68d2f176be87b19f57fd4772f3b35789308b7985195bbe41a9",
    // A sender at nonce 2^64 - 1 ends the simulation with -32603, validation or not.
    "ethSimulate-overflow-nonce" -> ""
  )

  for (name, accessListHash) <- vectors do
    "eth_simulateV1" should s"answer the execution-apis vector $name" taggedAs (UnitTest, RPCTest) in {
      val (request, expected) = vector(name)
      val actual = run(node, request)
      withoutMessages(actual \ "result") shouldBe withoutMessages(expected \ "result")
      withoutMessages(actual \ "error") shouldBe withoutMessages(expected \ "error")
      if accessListHash.nonEmpty then
        (actual \ "result")(0) \ "blockAccessListHash" shouldBe JString(accessListHash)
        (actual \ "result")(0) \ "slotNumber" shouldBe JString("0x4")
    }
