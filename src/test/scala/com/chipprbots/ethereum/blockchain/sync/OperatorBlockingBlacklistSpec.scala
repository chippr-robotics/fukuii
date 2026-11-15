package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor.PeerAddress
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 029 / B-277: the operator's block list is part of the node-wide blacklist, and only an [[OperatorBlock]] tells a
  * block from an automatic entry.
  */
class OperatorBlockingBlacklistSpec extends AnyFlatSpec with Matchers:

  private trait Setup:
    val registry = new BlockedIPRegistry(Set("10.0.0.1"))
    val underlying: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)
    val blacklist = new OperatorBlockingBlacklist(underlying, registry)

  "OperatorBlockingBlacklist" should "answer an OperatorBlock from the registry alone" taggedAs (UnitTest, NetworkTest) in
    new Setup:
      blacklist.isBlacklisted(OperatorBlock("10.0.0.1")) shouldBe true
      blacklist.isBlacklisted(OperatorBlock("10.0.0.2")) shouldBe false
      // An automatic entry for the address is not an operator block.
      underlying.add(PeerAddress("10.0.0.2"), 10.minutes, BlacklistReason.TcpSubsystemError)
      blacklist.isBlacklisted(OperatorBlock("10.0.0.2")) shouldBe false
      // A block made after the decorator was built counts at once.
      registry.block("10.0.0.3") shouldBe true
      blacklist.isBlacklisted(OperatorBlock("10.0.0.3")) shouldBe true
      registry.unblock("10.0.0.3") shouldBe true
      blacklist.isBlacklisted(OperatorBlock("10.0.0.3")) shouldBe false

  it should "answer a PeerAddress from the registry or the underlying blacklist" taggedAs (UnitTest, NetworkTest) in
    new Setup:
      blacklist.isBlacklisted(PeerAddress("10.0.0.1")) shouldBe true // registry only
      blacklist.isBlacklisted(PeerAddress("10.0.0.2")) shouldBe false
      underlying.add(PeerAddress("10.0.0.2"), 10.minutes, BlacklistReason.TcpSubsystemError)
      blacklist.isBlacklisted(PeerAddress("10.0.0.2")) shouldBe true // underlying only

  it should "leave other ids to the underlying blacklist" taggedAs (UnitTest, NetworkTest) in new Setup:
    val peer: BlacklistId = PeerId("ab" * 64)
    blacklist.isBlacklisted(peer) shouldBe false
    blacklist.add(peer, 10.minutes, BlacklistReason.TcpSubsystemError)
    blacklist.isBlacklisted(peer) shouldBe true
    blacklist.remove(peer)
    blacklist.isBlacklisted(peer) shouldBe false

  it should "list the registry's addresses among its keys, with the underlying ones" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Setup:
    blacklist.add(PeerAddress("10.0.0.2"), 10.minutes, BlacklistReason.TcpSubsystemError)
    blacklist.keys shouldBe Set[BlacklistId](PeerAddress("10.0.0.1"), PeerAddress("10.0.0.2"))

  it should "keep an operator block when the automatic entry for the same address is removed" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Setup:
    blacklist.add(PeerAddress("10.0.0.1"), 1.minute, BlacklistReason.TcpSubsystemError)
    blacklist.remove(PeerAddress("10.0.0.1"))
    blacklist.isBlacklisted(PeerAddress("10.0.0.1")) shouldBe true
    blacklist.isBlacklisted(OperatorBlock("10.0.0.1")) shouldBe true
