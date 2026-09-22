package com.chipprbots.ethereum.network.handshaker

import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Ratchet: the ETH status handshake must never substitute wall-clock time for a head block timestamp when computing
  * the EIP-2124 fork id.
  *
  * WHAT WENT WRONG. All three status-exchange sites carried
  * `if header.unixTimestamp == Timestamp.Zero then Timestamp(System.currentTimeMillis() / 1000)`. That treats a
  * timestamp of zero as "missing", but zero is real data: hive's engine fixtures and ETH mainnet both declare a genesis
  * timestamp of 0. On such a chain the substitution jumped the fork-id clock to the present, so every timestamp fork
  * looked already passed and we advertised `next=0` while a fork was genuinely upcoming. Measured across hive's entire
  * `Genesis=0` Fork ID family, e.g. `have 0x237d1525 next=0` against `want 0xc8014e7d next=1`.
  *
  * The genuine failure mode — a head header that is missing from storage — is now handled explicitly and logged, rather
  * than being conflated with a legitimate zero.
  *
  * WHY THIS IS A SOURCE RATCHET. After the fix, two of the three sites have no conditional left at all; they simply
  * read `bestBlockHeader.unixTimestamp`. There is no behaviour left to assert there — only the absence of a pattern
  * that must not come back. The behavioural half is pinned by `ForkIdGenesisTimestampSpec`, which asserts that a chain
  * with genesis timestamp 0 and a fork at a later timestamp announces that fork as upcoming.
  */
class HandshakeForkIdTimestampRatchetSpec extends AnyFlatSpec with Matchers:

  private val handshakerDir: Path =
    Path.of("src/main/scala/com/chipprbots/ethereum/network/handshaker")

  "the handshaker sources" should "be findable, so this ratchet cannot pass vacuously" in {
    // A ratchet pointed at a moved directory would go green forever. Fail loudly instead.
    Files.isDirectory(handshakerDir) shouldBe true
    scalaSources should not be empty
  }

  private def scalaSources: List[Path] =
    Files
      .list(handshakerDir)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".scala"))
      .toList

  it should "never substitute wall-clock time for a block timestamp" in {
    val offenders = scalaSources.filter { p =>
      Files.readString(p).contains("currentTimeMillis")
    }

    withClue(
      "A head timestamp of zero is data, not a missing value. If a head header is genuinely absent, " +
        "handle that case explicitly (and log it) instead of inventing a current time — substituting " +
        "wall-clock makes every timestamp fork look passed and breaks the fork id. Offending files: "
    ) {
      offenders.map(_.getFileName.toString) shouldBe empty
    }
  }
