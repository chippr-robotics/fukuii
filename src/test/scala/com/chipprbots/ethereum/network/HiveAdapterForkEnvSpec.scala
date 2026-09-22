package com.chipprbots.ethereum.network

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.*

/** Pins the contract between `hive/fukuii/fukuii.sh` and the environment hive actually hands a client.
  *
  * WHY THIS SPEC EXISTS. `ForkIdHiveRpcCompatSpec` asserts that fukuii computes 0xe272ecbe for the rpc-compat fixture
  * chain, and it passed the whole time the node was advertising 0xe54f18d6 on the wire. It asserts what `ForkId` does
  * with a config; nothing asserted that the hive adapter *builds* that config. The adapter read
  * `HIVE_FORK_MUIRGLACIER`, which hive never exports — the exported name is `HIVE_FORK_MUIR_GLACIER` — so Muir Glacier
  * stayed at the "not scheduled" sentinel, block 21 never entered the EIP-2124 checksum chain, and `eth_config`
  * returned a fork id short by exactly that one fork. A one-character gap between a shell script and a Go fixture, on
  * the far side of every Scala test in the repository.
  *
  * The two directions below are what would have caught it: no invented names, and no exported block fork left unread.
  */
class HiveAdapterForkEnvSpec extends AnyWordSpec with Matchers:

  /** Verbatim union of the `HIVE_*` keys in the two real fixtures the hive suites feed fukuii:
    *   - execution-apis `tests/forkenv.json` (the rpc-compat and graphql chain), and
    *   - go-ethereum `cmd/devp2p/internal/ethtest/testdata/forkenv.json` (the devp2p chain).
    *
    * `HIVE_TARGET_GAS_LIMIT` is in neither file: rpc-compat's `main.go` injects it when absent. It is listed because
    * the adapter legitimately reads it.
    */
  private val CanonicalHiveForkEnv: Set[String] = Set(
    "HIVE_AMSTERDAM_TIMESTAMP",
    "HIVE_BPO1_BLOB_BASE_FEE_UPDATE_FRACTION",
    "HIVE_BPO1_BLOB_MAX",
    "HIVE_BPO1_BLOB_TARGET",
    "HIVE_BPO1_TIMESTAMP",
    "HIVE_BPO2_BLOB_BASE_FEE_UPDATE_FRACTION",
    "HIVE_BPO2_BLOB_MAX",
    "HIVE_BPO2_BLOB_TARGET",
    "HIVE_BPO2_TIMESTAMP",
    "HIVE_CANCUN_BLOB_BASE_FEE_UPDATE_FRACTION",
    "HIVE_CANCUN_BLOB_MAX",
    "HIVE_CANCUN_BLOB_TARGET",
    "HIVE_CANCUN_TIMESTAMP",
    "HIVE_CHAIN_ID",
    "HIVE_DEPOSIT_CONTRACT_ADDRESS",
    "HIVE_FORK_ARROW_GLACIER",
    "HIVE_FORK_BERLIN",
    "HIVE_FORK_BYZANTIUM",
    "HIVE_FORK_CONSTANTINOPLE",
    "HIVE_FORK_GRAY_GLACIER",
    "HIVE_FORK_HOMESTEAD",
    "HIVE_FORK_ISTANBUL",
    "HIVE_FORK_LONDON",
    "HIVE_FORK_MUIR_GLACIER",
    "HIVE_FORK_PETERSBURG",
    "HIVE_FORK_SPURIOUS",
    "HIVE_FORK_TANGERINE",
    "HIVE_MERGE_BLOCK_ID",
    "HIVE_NETWORK_ID",
    "HIVE_OSAKA_BLOB_BASE_FEE_UPDATE_FRACTION",
    "HIVE_OSAKA_BLOB_MAX",
    "HIVE_OSAKA_BLOB_TARGET",
    "HIVE_OSAKA_TIMESTAMP",
    "HIVE_PRAGUE_BLOB_BASE_FEE_UPDATE_FRACTION",
    "HIVE_PRAGUE_BLOB_MAX",
    "HIVE_PRAGUE_BLOB_TARGET",
    "HIVE_PRAGUE_TIMESTAMP",
    "HIVE_SHANGHAI_TIMESTAMP",
    "HIVE_TARGET_GAS_LIMIT",
    "HIVE_TERMINAL_TOTAL_DIFFICULTY"
  )

  /** Names hive sets that are not part of the fork schedule. The adapter reads these too; they are excluded from the
    * "must be a fork-environment key" check rather than added to the canonical set, which stays a literal transcript of
    * the two fixtures.
    */
  private val NonForkHiveEnv: Set[String] = Set("HIVE_BOOTNODE", "HIVE_MINER", "HIVE_SKIP_POW")

  private val adapterPath = "hive/fukuii/fukuii.sh"

  /** sbt forks tests with the project base directory as cwd, but walk up anyway so the spec survives being run from a
    * module directory. Fails loudly rather than skipping: a silently-skipped contract test is the same as no test.
    */
  private lazy val adapterSource: String =
    def findUp(dir: File): Option[File] =
      if dir == null then None
      else
        val candidate = new File(dir, adapterPath)
        if candidate.isFile then Some(candidate) else findUp(dir.getParentFile)
    val file = findUp(new File(".").getCanonicalFile)
      .getOrElse(fail(s"could not locate $adapterPath from ${new File(".").getCanonicalPath}"))
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  /** Whole-line comments carry prose that names both the right and the wrong spelling on purpose, so they are dropped
    * before extraction: the question is what the script READS, not what it documents.
    */
  private lazy val referenced: Set[String] =
    val code = adapterSource.linesIterator.filterNot(_.trim.startsWith("#")).mkString("\n")
    "HIVE_[A-Z0-9_]+".r.findAllIn(code).toSet

  "the hive adapter's fork environment" must {

    "reference only names hive actually exports" taggedAs (UnitTest, NetworkTest) in {
      val invented = referenced -- CanonicalHiveForkEnv -- NonForkHiveEnv
      withClue(
        "fukuii.sh reads an environment variable hive never sets, so the value silently falls back to its default: "
      ) {
        invented shouldBe empty
      }
    }

    "read every block-numbered fork hive exports" taggedAs (UnitTest, NetworkTest) in {
      val unread = CanonicalHiveForkEnv.filter(_.startsWith("HIVE_FORK_")) -- referenced
      withClue("a fork hive schedules but fukuii.sh never reads is dropped from the EIP-2124 checksum chain: ") {
        unread shouldBe empty
      }
    }

    "read Muir Glacier under hive's underscored name, not the stale one" taggedAs (UnitTest, NetworkTest) in {
      // The direct pin on the measured defect: rpc-compat's forkenv.json exports
      // HIVE_FORK_MUIR_GLACIER=21. Reading HIVE_FORK_MUIRGLACIER instead produced 0xe54f18d6,
      // the fixture's checksum with block 21 removed, against an expected 0xe272ecbe.
      referenced.contains("HIVE_FORK_MUIR_GLACIER") shouldBe true
      referenced.contains("HIVE_FORK_MUIRGLACIER") shouldBe false
    }
  }
