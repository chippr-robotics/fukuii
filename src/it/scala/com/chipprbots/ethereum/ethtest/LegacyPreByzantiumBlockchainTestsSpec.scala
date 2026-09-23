package com.chipprbots.ethereum.ethtest

import java.io.File
import java.io.PrintWriter

import scala.io.Source

import io.circe.Json
import io.circe.parser.parse

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

/** Runs the pre-Byzantium slice (Frontier, Homestead, EIP150, EIP158) of the ethereum/legacytests BlockchainTests
  * through the in-process block-execution path, with full post-execution validation (state root, receipts root, gas,
  * bloom) on every block.
  *
  * Pre-Byzantium receipts carry the intermediate post-transaction STATE ROOT rather than a status byte, so this slice
  * is the only one that checks per-transaction state byte-for-byte — which is exactly the rule set ETC mainnet history
  * below Atlantis executes under.
  *
  * The fixtures are not vendored (the `ets/tests` submodule does not carry `LegacyTests`). Point
  * `FUKUII_LEGACY_TESTS_DIRS` at one or more (colon-separated) `LegacyTests/<Era>/BlockchainTests` checkouts of
  * https://github.com/ethereum/legacytests; the spec cancels when none is present.
  *
  * Optional: `FUKUII_LEGACY_TESTS_FILTER` (regex on the test name), `FUKUII_LEGACY_TESTS_REPORT` (file receiving one
  * line per failing test) and `FUKUII_LEGACY_TESTS_NETWORK_TYPE=ETC`.
  *
  * Blocks that the fixture marks invalid (`expectException*`, or carrying only raw `rlp`) are not imported: they are
  * never part of the canonical chain, so the valid blocks around them still determine the expected state. This runner
  * therefore checks valid-block execution only; rejection of the invalid blocks is covered by hive `consensus`.
  */
class LegacyPreByzantiumBlockchainTestsSpec extends EthereumTestsSpec:
  import LegacyPreByzantiumBlockchainTestsSpec.*

  private val defaultNetworks = Set("Frontier", "Homestead", "EIP150", "EIP158")

  /** Byzantium..Berlin: the block-number-dispatched ETH forks whose rules ETC runs as Atlantis, Agharta, Phoenix and
    * Magneto. (London and later are not modelled faithfully by `TestConverter`.)
    */
  private val postByzantiumNetworks = Set("Byzantium", "Constantinople", "ConstantinopleFix", "Istanbul", "Berlin")

  /** `FUKUII_LEGACY_TESTS_NETWORKS` (comma-separated) replaces a slice's network set, e.g. to replay the post-merge
    * copies of a vector. Such runs are report-only: the known-failure ratchets are not enforced.
    */
  private val networksOverride: Option[Set[String]] =
    sys.env.get("FUKUII_LEGACY_TESTS_NETWORKS").map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)

  /** `FUKUII_LEGACY_TESTS_NETWORK_TYPE=ETC` re-runs a slice under the ETC network type (TestConverter forces ETH), i.e.
    * the configuration ETC history is executed with. The fixtures encode ETH rules (e.g. EIP-7610, which ETC does not
    * apply), so such runs are report-only; compare their report with the ETH run's.
    */
  private val etcNetworkType: Boolean = sys.env.get("FUKUII_LEGACY_TESTS_NETWORK_TYPE").contains("ETC")

  private def configFor(network: String, fixtureChainId: Option[BigInt]): BlockchainConfig =
    val cfg = TestConverter.networkToConfig(network, baseBlockchainConfig)
    val networkType = if etcNetworkType then NetworkType.ETC else cfg.networkType
    // The fixtures are mainnet-ETH vectors: chain id 1 (as hive's genesis sets it) unless the fixture's
    // `config.chainid` says otherwise. The base config's chain id is ETC's, which breaks EIP-155 signatures and CHAINID.
    val chainId = ChainId(fixtureChainId.getOrElse(BigInt(1)))
    cfg.copy(networkType = networkType, chainId = chainId)

  private def fixtureChainId(test: Json): Option[BigInt] =
    test.hcursor.downField("config").downField("chainid").as[String].toOption.map { hex =>
      BigInt(hex.stripPrefix("0x"), 16)
    }

  private def canonicalBlocksOnly(test: Json): Json =
    test.hcursor
      .downField("blocks")
      .withFocus(_.mapArray(_.filter { block =>
        block.asObject.exists(o => o.contains("blockHeader") && !o.keys.exists(_.startsWith("expectException")))
      }))
      .top
      .getOrElse(test)

  /** The VM recurses once per call frame, and the 1024-deep call fixtures overflow the default 1 MiB test-thread stack
    * (the node itself runs with -Xss2M, see hive/fukuii/fukuii.sh). Execute on a thread with a larger stack.
    */
  private def onLargeStack[A](body: => A): Either[Throwable, A] =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "legacy-blockchain-test",
      64L * 1024 * 1024
    )
    thread.start()
    thread.join()
    result.get

  private def jsonFiles(root: File): Seq[File] =
    if root.isDirectory then root.listFiles().toSeq.sortBy(_.getName).flatMap(jsonFiles)
    else if root.getName.endsWith(".json") then Seq(root)
    else Seq.empty

  /** Runs every fixture of the slice under the roots in `FUKUII_LEGACY_TESTS_DIRS` and enforces the ratchet against
    * `knownFailures` — unless `FUKUII_LEGACY_TESTS_NETWORKS` overrides the network set (report-only).
    */
  private def runSlice(defaultSlice: Set[String], knownFailures: Set[String]): Unit =
    val sliceNetworks = networksOverride.getOrElse(defaultSlice)
    val enforce = networksOverride.isEmpty && !etcNetworkType
    val roots = sys.env
      .get("FUKUII_LEGACY_TESTS_DIRS")
      .toSeq
      .flatMap(_.split(":").toSeq)
      .map(new File(_))
      .filter(_.isDirectory)
    assume(roots.nonEmpty, "FUKUII_LEGACY_TESTS_DIRS not set or not a directory")
    val nameFilter = sys.env.get("FUKUII_LEGACY_TESTS_FILTER").map(_.r)

    // Failures stream to the report as they happen, so a crashed fork still leaves evidence.
    val report = sys.env.get("FUKUII_LEGACY_TESTS_REPORT").map(path => new PrintWriter(path))
    val counts = scala.collection.mutable.Map.empty[String, (Int, Int)].withDefaultValue((0, 0))
    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    val ran = scala.collection.mutable.Set.empty[String]

    for
      root <- roots
      file <- jsonFiles(root)
    do
      val source = Source.fromFile(file)
      val text =
        try source.mkString
        finally source.close()
      val suite = parse(text).flatMap(_.as[Map[String, Json]]).fold(e => fail(s"$file: $e"), identity)
      for (name, raw) <- suite.toSeq.sortBy(_._1) do
        val network = raw.hcursor.downField("network").as[String].getOrElse("")
        if sliceNetworks.contains(network) && nameFilter.forall(_.findFirstIn(name).isDefined) then
          val result = canonicalBlocksOnly(raw).as[BlockchainTest] match
            case Left(err)   => Left(s"decode: ${err.getMessage}")
            case Right(test) =>
              // EthereumTestExecutor.executeTest re-derives the config from the network name, so the helper is
              // driven directly. executeAndValidateBlock checks state root, receipts root, gasUsed and bloom on
              // every block; the last imported block must also be the fixture's `lastblockhash`.
              val expectedHead = raw.hcursor.downField("lastblockhash").as[String].toOption.map(_.stripPrefix("0x"))
              // A Throwable escaping execution (e.g. OutOfMemoryError on a memory-expansion fixture) is recorded as
              // this test's failure — it still fails the ratchet — instead of aborting the remaining fixtures.
              onLargeStack(
                new ReceiptValidatingHelper(using configFor(network, fixtureChainId(raw)))
                  .setupAndExecuteTest(test.pre, test.blocks, test.genesisBlockHeader)
              ).left.map(t => s"uncaught ${t.getClass.getName}: ${t.getMessage}").flatten.flatMap { _ =>
                val headers = (test.genesisBlockHeader.toSeq ++ test.blocks.map(_.blockHeader))
                  .map(TestConverter.toBlockHeader)
                // Fork-choice fixtures (side chains, reorgs) import non-canonical blocks too; their head is decided
                // by total difficulty, which this sequential runner does not model — only linear chains are checked.
                val linear = headers.zip(headers.drop(1)).forall { case (parent, child) => parent.isParentOf(child) }
                val head = headers.lastOption.map(_.hashAsHexString.stripPrefix("0x"))
                if !linear || expectedHead.isEmpty || head == expectedHead then Right(())
                else Left(s"head ${head.getOrElse("none")} != lastblockhash ${expectedHead.get}")
              }
          ran += name
          val (p, f) = counts(network)
          result match
            case Right(_) => counts(network) = (p + 1, f)
            case Left(err) =>
              counts(network) = (p, f + 1)
              val line = s"$network\t$name\t${file.getPath}\t${err.linesIterator.nextOption().getOrElse("")}"
              failures += line
              report.foreach { out =>
                out.println(line); out.flush()
              }

    sliceNetworks.toSeq.sorted.foreach { n =>
      val (p, f) = counts(n)
      info(s"$n: ${p + f} run, $p passed, $f failed")
    }
    val (tp, tf) = counts.values.foldLeft((0, 0)) { case ((a, b), (p, f)) => (a + p, b + f) }
    info(s"TOTAL: ${tp + tf} run, $tp passed, $tf failed")
    report.foreach { out =>
      try
        sliceNetworks.toSeq.sorted.foreach { n =>
          val (p, f) = counts(n); out.println(s"# $n run=${p + f} passed=$p failed=$f")
        }
      finally out.close()
    }
    failures.take(50).foreach(f => info(s"FAIL $f"))

    // Ratchet: no failure outside the known list, and a known failure that starts passing must be removed from it.
    if enforce then
      val failedNames = failures.map(_.split('\t')(1)).toSet
      val unexpected = failures.filterNot { f =>
        val name = f.split('\t')(1)
        knownFailures.contains(name) || HeapBound.contains(name)
      }
      unexpected shouldBe empty
      withClue("known failures that now pass (remove them from the known list): ") {
        (knownFailures.intersect(ran) -- failedNames).toSeq.sorted shouldBe empty
      }

  "Legacy pre-Byzantium BlockchainTests" should "execute every valid block of the Frontier/Homestead/EIP150/EIP158 slice" taggedAs (
    EthereumTest,
    SlowTest
  ) in {
    runSlice(defaultNetworks, KnownFailures)
  }

  "Legacy Byzantium..Berlin BlockchainTests" should "execute every valid block of the Byzantium..Berlin slice" taggedAs (
    EthereumTest,
    SlowTest
  ) in {
    runSlice(postByzantiumNetworks, KnownFailuresPostByzantium)
  }

object LegacyPreByzantiumBlockchainTestsSpec:

  /** Pre-existing divergences in this slice, each a separate consensus fix (measured against legacytests
    * 1f581b8ccdc4c63acf5f2c5c1b155c690c32a8eb, LegacyTests/Cancun/BlockchainTests). All fail on gasUsed.
    */
  /** Fixtures whose outcome depends on the test JVM's heap, not on consensus logic: exempt from both ratchet checks.
    * randomStatetest94 expands EVM memory to ~1.5 GB (header gasUsed 0x0449e8c0a3dd); with `Memory`'s ByteString
    * representation it passes under -Xmx4g in-process and exhausts the heap below that (hive runs it under a smaller
    * heap and fails there too). Tracked as a memory-footprint issue, not a rule divergence.
    */
  val HeapBound: Set[String] =
    Seq("Homestead", "Byzantium", "Constantinople", "ConstantinopleFix").map(n => s"randomStatetest94_$n").toSet

  /** Byzantium..Berlin slice (legacytests 1f581b8c, Constantinople + Cancun BlockchainTests): 63 pre-existing failures,
    * all the EIP-2681 CREATE/CREATE2 nonce-cap class (see KnownFailures) — being fixed separately.
    */
  val KnownFailuresPostByzantium: Set[String] =
    val create2Forks = Seq("Constantinople", "ConstantinopleFix", "Istanbul", "Berlin")
    Seq("Byzantium", "Constantinople", "ConstantinopleFix", "Istanbul", "Berlin")
      .map(n => s"CREATE_HighNonce_d0g0v0_$n")
      .toSet ++
      create2Forks.map(n => s"CREATE2_HighNonce_d0g0v0_$n").toSet ++
      Seq(5, 6, 7, 8, 9, 11).map(d => s"CREATE2_HighNonceDelegatecall_d${d}g0v0_Byzantium").toSet ++
      (for
        d <- Seq(5, 6, 7, 8, 9, 11, 17, 18, 19, 20, 21, 23)
        n <- create2Forks
      yield s"CREATE2_HighNonceDelegatecall_d${d}g0v0_$n").toSet

  val KnownFailures: Set[String] =
    // EIP-2681 nonce cap (2^64-1) on CREATE/CREATE2 from an account at max nonce: CreateOp applies the cap only
    // inside the Amsterdam charge decision, so pre-Amsterdam the create proceeds and the gas differs.
    Seq("Frontier", "Homestead", "EIP150", "EIP158").map(n => s"CREATE_HighNonce_d0g0v0_$n").toSet ++
      (for
        d <- Seq(5, 6, 7, 8, 9, 11)
        n <- Seq("Homestead", "EIP150", "EIP158")
      yield s"CREATE2_HighNonceDelegatecall_d${d}g0v0_$n").toSet

  /** `EthereumTestHelper` inherits `MockValidatorsAlwaysSucceed`, under which `executeAndValidateBlock` checks only
    * gasUsed and the state root: the receipts root and logs bloom are never compared, so a wrong intermediate state
    * root in a pre-Byzantium receipt passes silently. Swap in the real block-body/receipt and transaction validators.
    * Header and ommer validation stay mocked — the fixtures are NoProof (zero nonce/mixHash) and carry no Ethash seal.
    */
  object ReceiptValidatingValidators extends MockValidatorsAlwaysSucceed:
    override val blockValidator: BlockValidator = StdBlockValidator
    override val signedTransactionValidator: SignedTransactionValidator = StdSignedTransactionValidator

  final class ReceiptValidatingHelper(using BlockchainConfig) extends EthereumTestHelper:
    override lazy val validators: Validators = ReceiptValidatingValidators
