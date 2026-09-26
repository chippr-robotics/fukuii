package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.network.p2p.MessageDecoder.MalformedMessageError
import com.chipprbots.ethereum.network.p2p.MessageDecoder.UnknownMessageTypeError
import com.chipprbots.ethereum.network.p2p.SNAP2MessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccessLists.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccessLists.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.*
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*

/** Wire-format and dispatch tests for snap/2 (EIP-8189).
  *
  * Reference: go-ethereum master `eth/protocols/snap/protocol.go` (protocolLengths = {SNAP1: 8, SNAP2: 10}) and
  * `handler.go` (the `snap2` handler map has no entry for GetTrieNodesMsg/TrieNodesMsg — an arriving one falls to
  * `errInvalidMsgCode` and tears the connection down).
  */
class SNAP2MessagesSpec extends AnyWordSpec with Matchers:

  private def hash(b: Int): ByteString = ByteString(Array.fill(32)(b.toByte))

  // RLPValue wraps a raw Array[Byte] with no custom equals/hashCode, so two content-equal
  // RLPValues built from separately-allocated arrays are never `==` to each other — compare by
  // RLP-encoded bytes instead, which DOES have structural Seq equality.
  private def rlpBytes(e: com.chipprbots.ethereum.rlp.RLPEncodeable): Seq[Byte] =
    com.chipprbots.ethereum.rlp.encode(e).toSeq

  "SNAP2MessageDecoder" should {

    "still decode GetAccountRange unchanged from snap/1" taggedAs UnitTest in {
      val msg = GetAccountRange(BigInt(1), hash(0x11), hash(0x22), hash(0x33), BigInt(1024))
      SNAP2MessageDecoder.fromBytes(Codes.GetAccountRangeCode, msg.toBytes) match
        case Right(r: GetAccountRange) => r shouldEqual msg
        case other                     => fail(s"Expected GetAccountRange, got $other")
    }

    "round-trip GetAccessLists (0x08)" taggedAs UnitTest in {
      val hashes = Seq(hash(1), hash(2))
      val msg = GetAccessLists(BigInt(5), hashes, BigInt(2 * 1024 * 1024))
      SNAP2MessageDecoder.fromBytes(Codes.GetAccessListsCode, msg.toBytes) match
        case Right(r: GetAccessLists) =>
          r.requestId shouldEqual BigInt(5)
          r.hashes shouldEqual hashes
        case other => fail(s"Expected GetAccessLists, got $other")
    }

    "round-trip AccessLists (0x09) with an unavailable entry as the RLP empty string" taggedAs UnitTest in {
      val msg = AccessLists(BigInt(6), Seq(RLPValue(Array.emptyByteArray)))
      SNAP2MessageDecoder.fromBytes(Codes.AccessListsCode, msg.toBytes) match
        case Right(r: AccessLists) =>
          r.requestId shouldEqual BigInt(6)
          r.accessLists.map(rlpBytes) shouldEqual Seq(RLPValue(Array.emptyByteArray)).map(rlpBytes)
        case other => fail(s"Expected AccessLists, got $other")
    }

    // EIP-8189 removes GetTrieNodes/TrieNodes from snap/2. A MalformedMessageError (not
    // UnknownMessageTypeError) is what makes RLPxConnectionHandler.processMessage disconnect the
    // peer instead of silently skipping the message — same idiom as GetNodeData/NodeData on ETH68+.
    "reject GetTrieNodes (0x06) as a disconnect-worthy protocol violation, not a silent skip" taggedAs UnitTest in {
      val msg = GetTrieNodes(BigInt(1), hash(0xaa), Seq(Seq(hash(0xbb))), BigInt(1024))
      SNAP2MessageDecoder.fromBytes(Codes.GetTrieNodesCode, msg.toBytes) match
        case Left(_: MalformedMessageError)   => succeed
        case Left(_: UnknownMessageTypeError) => fail("GetTrieNodes must disconnect, not silently skip, on snap/2")
        case other                            => fail(s"Expected Left(MalformedMessageError), got $other")
    }

    "reject TrieNodes (0x07) the same way" taggedAs UnitTest in {
      SNAP2MessageDecoder.fromBytes(Codes.TrieNodesCode, Array.emptyByteArray) match
        case Left(_: MalformedMessageError) => succeed
        case other                          => fail(s"Expected Left(MalformedMessageError), got $other")
    }
  }

  "Capability" should {
    "advertise snap/2 and negotiate it when both sides support it" taggedAs UnitTest in {
      Capability.negotiateSnap(
        List(Capability.SNAP2),
        List(Capability.SNAP1, Capability.SNAP2)
      ) shouldBe Some(Capability.SNAP2)
    }

    "fall back to snap/1 when the peer only advertises snap/1 (core-geth / older clients)" taggedAs UnitTest in {
      Capability.negotiateSnap(
        List(Capability.SNAP1),
        List(Capability.SNAP1, Capability.SNAP2)
      ) shouldBe Some(Capability.SNAP1)
    }

    "negotiate no snap capability when the peer offers none" taggedAs UnitTest in {
      Capability.negotiateSnap(List.empty, List(Capability.SNAP1, Capability.SNAP2)) shouldBe None
    }

    "parse and round-trip the snap/2 wire string" taggedAs UnitTest in {
      Capability.parse("snap/2") shouldBe Some(Capability.SNAP2)
      Capability.usesRequestId(Capability.SNAP2) shouldBe true
    }
  }

  "SnapServer.serveAccessLists" should {
    "answer every requested hash with an empty entry, preserving order (no BAL storage yet)" taggedAs UnitTest in {
      import com.chipprbots.ethereum.network.snapserver.SnapServer
      val hashes = Seq(hash(1), hash(2), hash(3))
      val response = SnapServer.serveAccessLists(BigInt(42), hashes)
      response.requestId shouldEqual BigInt(42)
      response.accessLists.map(rlpBytes) shouldEqual Seq.fill(3)(RLPValue(Array.emptyByteArray)).map(rlpBytes)
    }

    "answer an empty request with an empty list, no error" taggedAs UnitTest in {
      import com.chipprbots.ethereum.network.snapserver.SnapServer
      val response = SnapServer.serveAccessLists(BigInt(1), Seq.empty)
      response.accessLists shouldBe empty
    }
  }
