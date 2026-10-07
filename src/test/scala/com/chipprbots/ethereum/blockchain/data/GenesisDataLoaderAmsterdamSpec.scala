package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** An Amsterdam genesis built by [[GenesisDataLoader]] from a fixture's `pre` and genesis header fields must hash to
  * the fixture's genesis, the loader producing the header rather than the test copying the fixture's.
  *
  * Amsterdam at the genesis timestamp needs the 23-field header: go-ethereum `core/genesis.go` sets
  * `BlockAccessListHash = EmptyBlockAccessListHash` (genesis executes nothing) and `SlotNumber = genesis.SlotNumber`, 0
  * when absent. Before this, the loader built the 21-field Prague header and so a different hash.
  *
  * Vectors (`src/test/resources/eest-regression/amsterdam-genesis.json`): five execution-specs `tests@v21.0.0`
  * blockchain tests, verbatim, trimmed to `network`, `config.chainid`, `pre`, `genesisBlockHeader` and `genesisRLP`:
  * the genesis slot 999 of `test_slotnum_genesis`, a non-zero `excessBlobGas`, a different gas limit and base fee, a
  * plain Amsterdam genesis, and a `BPO2ToAmsterdamAtTime15k` genesis that must keep the Prague shape because Amsterdam
  * is not yet active at its timestamp.
  *
  * The corpus-wide check runs only with `EEST_FIXTURES` set, like `EestFixtureCorpusSpec`.
  */
class GenesisDataLoaderAmsterdamSpec extends AnyFlatSpec with Matchers:

  private def str(v: JValue): String = v.values.toString
  private def bytes(s: String): ByteString = ByteString(Hex.decode(s.stripPrefix("0x")))
  private def quantity(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def hex(b: ByteString): String = "0x" + Hex.toHexString(b.toArray)
  private def opt(v: JValue): Option[String] = v.toOption.map(str)

  private def alloc(pre: JValue): Map[String, GenesisAccount] =
    val JObject(accounts) = pre: @unchecked
    accounts.map { case (address, account) =>
      val code = bytes(str(account \ "code"))
      val JObject(storage) = account \ "storage": @unchecked
      address.stripPrefix("0x") -> GenesisAccount(
        precompiled = None,
        balance = UInt256(quantity(str(account \ "balance"))),
        code = Option.when(code.nonEmpty)(code),
        nonce = Some(UInt256(quantity(str(account \ "nonce")))),
        storage = Option.when(storage.nonEmpty)(storage.map { case (k, v) =>
          UInt256(quantity(k)) -> UInt256(quantity(str(v)))
        }.toMap)
      )
    }.toMap

  /** The fixture's genesis inputs as a genesis file would carry them: header quantities as hex strings, alloc = `pre`.
    */
  private def genesisData(t: JValue): GenesisData =
    val gh = t \ "genesisBlockHeader"
    GenesisData(
      nonce = bytes(str(gh \ "nonce")),
      mixHash = Some(bytes(str(gh \ "mixHash"))),
      difficulty = str(gh \ "difficulty"),
      extraData = bytes(str(gh \ "extraData")),
      gasLimit = str(gh \ "gasLimit"),
      coinbase = bytes(str(gh \ "coinbase")),
      timestamp = str(gh \ "timestamp"),
      alloc = alloc(t \ "pre"),
      baseFeePerGas = opt(gh \ "baseFeePerGas"),
      excessBlobGas = opt(gh \ "excessBlobGas"),
      blobGasUsed = opt(gh \ "blobGasUsed"),
      slotNumber = opt(gh \ "slotNumber")
    )

  /** Loads the fixture's genesis into a fresh chain and returns the header the loader stored. */
  private def loadedGenesis(t: JValue): Either[String, BlockHeader] =
    val env = new EphemBlockchainTestSetup {}
    val chainId = (t \ "config" \ "chainid").toOption.map(v => quantity(str(v))).getOrElse(BigInt(1))
    EestBlockchainReplay.configFor(env.blockchainConfig, str(t \ "network"), chainId).flatMap { config =>
      given BlockchainConfig = config
      new GenesisDataLoader(
        env.blockchainReader,
        env.blockchainWriter,
        env.storagesInstance.storages.evmCodeStorage,
        env.storagesInstance.storages.stateStorage
      ).loadGenesisData(genesisData(t))
        .toEither
        .left
        .map(e => s"loader failed: $e")
        .flatMap(_ => env.blockchainReader.getBlockHeaderByNumber(0).toRight("loader stored no genesis"))
    }

  private lazy val vectors: List[(String, JValue)] =
    val src = scala.io.Source.fromResource("eest-regression/amsterdam-genesis.json")
    val JObject(tests) = (try parse(src.mkString)
    finally src.close()): @unchecked
    tests

  private def vector(namePart: String): JValue =
    vectors.collectFirst { case (name, t) if name.contains(namePart) => t }.getOrElse(fail(s"no vector $namePart"))

  "GenesisDataLoader" should "reproduce every vector's genesis hash from its pre-state and header fields" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    vectors should have size 5
    vectors.foreach { case (name, t) =>
      withClue(s"$name: ") {
        val expected = str(t \ "genesisBlockHeader" \ "hash")
        // The fixture's own RLP hashes to the same value, so the JSON header is the release's.
        hex(Hex.decode(str(t \ "genesisRLP").stripPrefix("0x")).toBlock.header.hash.value) shouldBe expected
        loadedGenesis(t).map(h => hex(h.hash.value)) shouldBe Right(expected)
      }
    }
  }

  it should "give an Amsterdam genesis the empty access-list commitment and slot 0 when none is declared" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val Right(header) = loadedGenesis(vector("test_extra_logs")): @unchecked
    header.extraFields shouldBe a[HefPostAmsterdam]
    header.blockAccessListHash shouldBe Some(BlockAccessList.EmptyHash)
    header.slotNumber shouldBe Some(BigInt(0))
  }

  it should "carry a declared genesis slotNumber into the header (test_slotnum_genesis: 999)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val Right(header) = loadedGenesis(vector("test_slotnum_genesis")): @unchecked
    header.slotNumber shouldBe Some(BigInt(999))
    header.blockAccessListHash shouldBe Some(BlockAccessList.EmptyHash)
  }

  it should "keep the Prague shape when Amsterdam activates after the genesis timestamp" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val Right(header) = loadedGenesis(vector("fork_BPO2ToAmsterdamAtTime15k")): @unchecked
    header.extraFields shouldBe a[HefPostPrague]
    header.blockAccessListHash shouldBe None
    header.slotNumber shouldBe None
  }

  // Opt-in: the whole corpus, when one is configured. Canceled, not passed, without it.
  "every execution-specs Amsterdam genesis" should "be reproduced by the loader" taggedAs (
    EthereumTest,
    SlowTest,
    ConsensusTest
  ) in {
    def setting(name: String): Option[String] =
      sys.props.get(s"eest.${name.toLowerCase}").orElse(sys.env.get(s"EEST_$name")).filter(_.nonEmpty)
    val root = setting("FIXTURES")
      .map(Paths.get(_))
      .getOrElse(cancel("no corpus configured: set EEST_FIXTURES (see scripts/eest/fetch_fixtures.py)"))
    val filter = setting("FILTER").map(_.r)
    val threads = setting("THREADS").map(_.toInt).getOrElse(Runtime.getRuntime.availableProcessors)
    val files: Seq[Path] = Using.resource(Files.walk(root)) { walk =>
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json"))
        // The release's `.meta/index.json` is an index, not a fixture (as in EestFixtureCorpusSpec).
        .filterNot(p => root.relativize(p).toString.split('/').exists(_.startsWith(".")))
        .filter(p => filter.forall(_.findFirstIn(root.relativize(p).toString).isDefined))
        .toSeq
        .sorted
    }
    files should not be empty

    // Every Throwable is that test's (or that file's) mismatch, fatal ones included: an executor swallows what a task
    // throws, so an OutOfMemoryError would otherwise drop the rest of the file from the count and still pass.
    val mismatches = new ConcurrentLinkedQueue[String]()
    val checked = new java.util.concurrent.atomic.AtomicInteger()
    val pool = Executors.newFixedThreadPool(threads)
    files.foreach { file =>
      pool.execute { () =>
        val relative = root.relativize(file)
        try
          val JObject(tests) = parse(Files.readString(file)): @unchecked
          tests.foreach { case (name, t) =>
            checked.incrementAndGet()
            val expected = str(t \ "genesisBlockHeader" \ "hash")
            val got =
              try loadedGenesis(t).map(h => hex(h.hash.value))
              catch case e: Throwable => Left(s"threw $e")
            if got != Right(expected) then mismatches.add(s"$relative :: $name: $got != $expected")
          }
        catch case e: Throwable => mismatches.add(s"$relative :: <file>: unreadable: $e")
      }
    }
    pool.shutdown()
    pool.awaitTermination(12, TimeUnit.HOURS) shouldBe true
    info(s"${checked.get} genesis blocks checked, ${mismatches.size} mismatched")
    mismatches.asScala.toSeq.sorted.take(20) shouldBe empty
  }
