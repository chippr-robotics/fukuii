package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import java.util.concurrent.atomic.AtomicInteger

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_getPayloadV{n} serves only payloads of its own fork window and answers -38005 for any other, in go-ethereum's
  * windows (`checkFork` per version, eth/catalyst/api.go):
  *
  *   - V1: Paris. go-ethereum checks only the payload ID's version here; fukuii keeps V1 to Paris, its documented
  *     policy for the V1 methods (EngineApiController.payloadAttributesVersionError).
  *   - V2: Paris, Shanghai.
  *   - V3: Cancun.
  *   - V4: Prague.
  *   - V5: Osaka and the blob-parameter-only forks after it (BPO1..).
  *   - V6 (Amsterdam) does not exist here, so no version serves an Amsterdam payload.
  *
  * execution-apis states it per method: "Client software MUST return -38005: Unsupported fork error if the timestamp of
  * the built payload does not fall within the time frame of the <fork> fork" (cancun.md getPayloadV3, prague.md
  * getPayloadV4, osaka.md getPayloadV5; cancun.md's update of getPayloadV2).
  *
  * The defect: V3 served every payload from Cancun on (Prague, Osaka, Amsterdam), V4 served every payload before Osaka
  * (Paris, Shanghai, Cancun), and V5 served Amsterdam.
  */
class EngineApiGetPayloadForkWindowSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  // The fork sentinels of src/test/resources/application.conf, then two more declared on a copy of that config.
  private val ParisTs = 1001L
  private val ShanghaiTs = 9999999994L
  private val CancunTs = 9999999995L
  private val PragueTs = 9999999998L
  private val OsakaTs = 9999999999L
  private val Bpo1Ts = 10000000001L
  private val AmsterdamTs = 10000000003L

  private val testConfig = Config.blockchains.blockchainConfig
  private val withBpo1AndAmsterdam = testConfig.copy(forkTimestamps =
    testConfig.forkTimestamps.copy(bpo1Timestamp = Some(Bpo1Ts), amsterdamTimestamp = Some(AmsterdamTs))
  )

  private val forkAt: Seq[(String, Long)] = Seq(
    "Paris" -> ParisTs,
    "Shanghai" -> ShanghaiTs,
    "Cancun" -> CancunTs,
    "Prague" -> PragueTs,
    "Osaka" -> OsakaTs,
    "BPO1" -> Bpo1Ts,
    "Amsterdam" -> AmsterdamTs
  )

  /** go-ethereum's window per getPayload version (V1: fukuii's Paris policy, see above). */
  private val window: Seq[(Int, Set[String])] = Seq(
    1 -> Set("Paris"),
    2 -> Set("Paris", "Shanghai"),
    3 -> Set("Cancun"),
    4 -> Set("Prague"),
    5 -> Set("Osaka", "BPO1")
  )

  "EngineApiController.getPayloadForkError" should {

    "refuse exactly the payloads outside each version's fork window" taggedAs (UnitTest, ConsensusTest) in {
      val wrong =
        for
          (version, served) <- window
          (fork, ts) <- forkAt
          refused = EngineApiController.getPayloadForkError(version, Timestamp(ts), withBpo1AndAmsterdam).isDefined
          if refused == served.contains(fork)
        yield s"getPayloadV$version ${if refused then "refused" else "served"} a $fork payload"
      wrong shouldBe empty
    }
  }

  /** A service whose payload is `block`, counting how often the controller resolves it. */
  private class CountingService(block: Block) extends EngineApiService(null, null, null, null, None)(null, null):
    val resolved = new AtomicInteger(0)
    override def getPayload(payloadId: ByteString): IO[Either[String, Block]] = IO.pure(Right(block))
    override def resolvePayload(payloadId: ByteString): IO[Either[String, ServedPayload]] =
      IO(resolved.incrementAndGet()).as(Right(ServedPayload(block, Nil, Nil, BlobsBundleData(Nil, Nil, Nil, Nil))))

  private def extraFieldsFor(fork: String): HeaderExtraFields =
    val zero32 = ByteString(new Array[Byte](32))
    fork match
      case "Paris"    => HefPostOlympia(BigInt("1000000000"))
      case "Shanghai" => HefPostShanghai(BigInt("1000000000"), BlockHeader.EmptyMpt)
      case "Cancun"   => HefPostCancun(BigInt("1000000000"), BlockHeader.EmptyMpt, BigInt(0), BigInt(0), zero32)
      case _          => HefPostPrague(BigInt("1000000000"), BlockHeader.EmptyMpt, BigInt(0), BigInt(0), zero32, zero32)

  private def payloadAt(fork: String, ts: Long): Block =
    val header = BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(ByteString(new Array[Byte](32))),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(1),
      gasLimit = GasAmount(30000000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(ts),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = extraFieldsFor(fork)
    )
    val withdrawals = Option.when(fork != "Paris")(Seq.empty[Withdrawal])
    Block(header, BlockBody(Nil, Nil, withdrawals = withdrawals))

  "engine_getPayloadV1..V5" should {

    "answer -38005 outside the version's window, before resolving the payload, and serve it inside" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // The controller reads the global test config, which declares forks up to Osaka.
      val wrong =
        for
          (version, served) <- window
          (fork, ts) <- forkAt.filterNot { case (fork, _) => fork == "BPO1" || fork == "Amsterdam" }
          service = new CountingService(payloadAt(fork, ts))
          response = new EngineApiController(service)
            .handleRequest(
              JsonRpcRequest(
                "2.0",
                s"engine_getPayloadV$version",
                Some(JArray(List(JString("0x0000000000000001")))),
                Some(JInt(1))
              )
            )
            .unsafeRunSync()
          outcome =
            if served.contains(fork) then
              Option.when(response.error.isDefined || service.resolved.get != 1)(s"error ${response.error}")
            else
              Option.when(response.error.map(_.code) != Some(-38005) || service.resolved.get != 0)(
                s"error ${response.error.map(_.code)}, resolved ${service.resolved.get} time(s)"
              )
          problem <- outcome
        yield s"getPayloadV$version for a $fork payload: $problem"
      wrong shouldBe empty
    }
  }
