package com.chipprbots.ethereum.network

import scala.concurrent.duration.*

import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.testing.LogCapture
import com.chipprbots.ethereum.testing.Tags.*

/** B-415: a node on a public network meets a stranger every few seconds (fork-id check per discovered record and per
  * handshake, a blacklisting per refused peer). None of these may be INFO, or a busy node's log fills the disk (12.5 GB
  * in ten hours on Mordor). Each is DEBUG at its source and counted in [[PeerChurnStats]].
  */
class PeerLogLevelSpec extends AnyFlatSpec with Matchers:

  "CacheBasedBlacklist" should "log an added peer at DEBUG and count it in the churn summary" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val before = PeerChurnStats.global.current.blacklisted
    val blacklist = CacheBasedBlacklist.empty(10)
    val (_, events) = LogCapture.capture("com.chipprbots.ethereum.blockchain.sync") {
      blacklist.add(PeerId("peer-b415"), 1.minute, BlacklistReason.ErrorInBlockHeaders)
    }
    LogCapture.atInfoOrAbove(events) shouldBe empty
    events.exists(e => e.getLevel == Level.DEBUG && e.getFormattedMessage.contains("Blacklisting peer")) shouldBe true
    PeerChurnStats.global.current.blacklisted should be >= (before + 1)
  }
