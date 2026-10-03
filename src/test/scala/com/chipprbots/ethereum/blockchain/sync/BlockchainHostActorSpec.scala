package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.language.postfixOps

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs.*
import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.BlockAccessList.CodeChange
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.PlatabergetBalVectors
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class BlockchainHostActorSpec extends AnyFlatSpec with Matchers:

  it should "return Receipts for block hashes" taggedAs (UnitTest) in new TestSetup:
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
      Set(
        Codes.GetPooledTransactionsCode,
        Codes.GetNodeDataCode,
        Codes.GetReceiptsCode,
        Codes.GetBlockBodiesCode,
        Codes.GetBlockHeadersCode,
        // eth/71 (EIP-8159) and eth/72 (EIP-8070): served alongside the other ETH request codes.
        Codes.GetBlockAccessListsCode,
        Codes.GetCellsCode
      ),
      PeerSelector.AllPeers
    )

    // given
    val receiptsHashes: Seq[ByteString] = Seq(
      ByteString(Hex.decode("a218e2c611f21232d857e3c8cecdcdf1f65f25a4477f98f6f47e4063807f2308")),
      ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
    )

    val receipts: Seq[Seq[Receipt]] = Seq(Seq(), Seq())

    blockchainWriter
      .storeReceipts(BlockHash(receiptsHashes.head), receipts.head)
      .and(blockchainWriter.storeReceipts(BlockHash(receiptsHashes(1)), receipts(1)))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetReceipts(BigInt(0), receiptsHashes), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETHPackets.Receipts68(
          BigInt(0),
          com.chipprbots.ethereum.rlp
            .RLPList(receipts.map(rs => com.chipprbots.ethereum.rlp.RLPList(rs.map(_.toRLPEncodable)*))*)
        ),
        peerId
      )
    )

  // A reply is matched to the request by position, so skipping a block we lack would shift every later block's
  // receipts onto the wrong hash. Stop at the first one, as go-ethereum does.
  it should "stop at the first block it has no receipts for, rather than skip it (eth/68 and eth/69)" taggedAs (
    UnitTest
  ) in new TestSetup:
    val known1: ByteString = ByteString(Hex.decode("11" * 32))
    val unknown: ByteString = ByteString(Hex.decode("22" * 32))
    val known2: ByteString = ByteString(Hex.decode("33" * 32))
    blockchainWriter
      .storeReceipts(BlockHash(known1), Seq.empty)
      .and(blockchainWriter.storeReceipts(BlockHash(known2), Seq.empty))
      .commit()
    val onlyKnown1 = com.chipprbots.ethereum.rlp.RLPList(com.chipprbots.ethereum.rlp.RLPList())

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetReceipts(BigInt(1), Seq(known1, unknown, known2)), peerId)
    )
    val eth68 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    ByteString(eth68.message.toBytes) shouldBe
      ByteString(ETHPackets.Receipts68.Receipts68Enc(ETHPackets.Receipts68(BigInt(1), onlyKnown1)).toBytes: Array[Byte])

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetReceipts69(BigInt(2), Seq(known1, unknown, known2)), peerId)
    )
    val eth69 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    ByteString(eth69.message.toBytes) shouldBe
      ByteString(ETHPackets.Receipts69.Receipts69Enc(ETHPackets.Receipts69(BigInt(2), onlyKnown1)).toBytes: Array[Byte])

  // ---- ETH70 GetReceipts (EIP-7706 partial receipt delivery) -----------------------------------------------
  //
  // Coverage for the eth/70 size cap and truncation semantics: fukuii capped a receipts response at 2 MiB
  // (softResponseLimit, the constant meant for headers/bodies/ETH69 receipts) instead of the 10 MiB
  // maxPacketSize go-ethereum's serviceGetReceiptsQuery70 actually uses for eth/70, and represented an
  // unfillable block as an empty-but-present placeholder (incomplete=true) where go-ethereum omits it
  // (incomplete=false). hive's TestGetLargeReceipts still reported an empty-trie receipt root after both were
  // fixed; that came from the shape of each receipt on the wire, pinned in ReceiptWireFormatSpec.

  private def paddedReceipt(dataSize: Int): Receipt =
    LegacyReceipt.withHashOutcome(
      postTransactionStateHash = ByteString(Array.fill[Byte](32)(1)),
      cumulativeGasUsed = 21000,
      logsBloomFilter = BloomFilter.Empty,
      logs = Seq(TxLogEntry(Address(0xaa), Seq.empty, ByteString(Array.fill[Byte](dataSize)(0))))
    )

  // ETH70's wire encoding drops the bloom filter (like ETH69) — the SAME shape BlockchainHostActor's
  // GetReceipts70 branch builds via ETHPackets.ReceiptBloomFreeEnc. Constructed explicitly (not via
  // `receipt.toRLPEncodable`) because this file's file-level `ReceiptCodecs.*` wildcard import (needed by the
  // bloom-INCLUDING ETH68 test above) already provides a same-named extension for `Receipt`, and Scala 3
  // prefers that native `extension` over a same-scope `implicit class`-provided one regardless of import
  // locality — so calling through the extension-method syntax here would silently encode the WRONG (bloom
  // filter included) shape.
  private def receiptsListRLP(receipts: Seq[Receipt]): RLPList =
    RLPList(receipts.map(r => ETHPackets.ReceiptBloomFreeEnc(r).toRLPEncodable)*)

  // Compares wire bytes, not `==` on the decoded message: `Receipts70` nests `RLPValue(bytes: Array[Byte])`,
  // and a case class's auto-derived `equals` compares an `Array` field by REFERENCE (JVM `Array#equals`), not
  // content — two independently-built receipt lists with byte-for-byte identical content would still (wrongly)
  // report as unequal. `ByteString` has proper content equality, so comparing the fully-encoded bytes is both
  // correct and closer to what an actual peer on the wire observes.
  private def wireBytes(msg: ETHPackets.Receipts70): ByteString =
    ByteString(ETHPackets.Receipts70.Receipts70Enc(msg).toBytes: Array[Byte])

  private def expectReceipts70(networkPeerManager: TestProbe, peerId: PeerId, expected: ETHPackets.Receipts70): Unit =
    val actual = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    actual.peerId shouldBe peerId
    ByteString(actual.message.toBytes) shouldBe wireBytes(expected)

  it should "serve receipts up to ~3 MiB in one response without truncating at the old 2 MiB limit" taggedAs (
    UnitTest
  ) in new TestSetup:
    // 3 receipts x ~1 MiB of log data each: over the OLD (wrong) 2 MiB cap, comfortably under the correct
    // 10 MiB maxPacketSize. Under the bug this fixes, this would have come back truncated
    // (lastBlockIncomplete=true, only 1-2 receipts) instead of complete.
    val hash: ByteString = ByteString(Hex.decode("a218e2c611f21232d857e3c8cecdcdf1f65f25a4477f98f6f47e4063807f2308"))
    val receipts: Seq[Receipt] = Seq.fill(3)(paddedReceipt(1024 * 1024))

    blockchainWriter.storeReceipts(BlockHash(hash), receipts).commit()

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetReceipts70(BigInt(0), firstBlockReceiptIndex = 0L, Seq(hash)), peerId)
    )

    expectReceipts70(
      networkPeerManager,
      peerId,
      ETHPackets.Receipts70(BigInt(0), lastBlockIncomplete = false, RLPList(receiptsListRLP(receipts)))
    )

  it should "stop serving (no placeholder entry) at the first unknown block, rather than skipping past it" taggedAs (
    UnitTest
  ) in new TestSetup:
    val unknownHash: ByteString = ByteString(Hex.decode("aa" * 32))
    val knownHash: ByteString = ByteString(Hex.decode("bb" * 32))
    val knownReceipts: Seq[Receipt] = Seq(paddedReceipt(16))

    // knownHash is stored, but comes AFTER unknownHash in the request — it must never be reached, let alone
    // have its receipts land in unknownHash's response slot.
    blockchainWriter.storeReceipts(BlockHash(knownHash), knownReceipts).commit()

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(
        ETHPackets.GetReceipts70(BigInt(0), firstBlockReceiptIndex = 0L, Seq(unknownHash, knownHash)),
        peerId
      )
    )

    expectReceipts70(
      networkPeerManager,
      peerId,
      ETHPackets.Receipts70(BigInt(0), lastBlockIncomplete = false, RLPList())
    )

  it should "return an explicit empty list (not a stop) for a block already fully resumed, and keep serving the rest" taggedAs (
    UnitTest
  ) in new TestSetup:
    val resumedHash: ByteString = ByteString(Hex.decode("cc" * 32))
    val nextHash: ByteString = ByteString(Hex.decode("dd" * 32))
    val resumedReceipts: Seq[Receipt] = Seq(paddedReceipt(16), paddedReceipt(16))
    val nextReceipts: Seq[Receipt] = Seq(paddedReceipt(16))

    blockchainWriter
      .storeReceipts(BlockHash(resumedHash), resumedReceipts)
      .and(blockchainWriter.storeReceipts(BlockHash(nextHash), nextReceipts))
      .commit()

    // firstBlockReceiptIndex == resumedHash's full receipt count: the client already has everything for it.
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(
        ETHPackets.GetReceipts70(
          BigInt(0),
          firstBlockReceiptIndex = resumedReceipts.size.toLong,
          Seq(resumedHash, nextHash)
        ),
        peerId
      )
    )

    expectReceipts70(
      networkPeerManager,
      peerId,
      ETHPackets.Receipts70(BigInt(0), lastBlockIncomplete = false, RLPList(RLPList(), receiptsListRLP(nextReceipts)))
    )

  it should "return BlockBodies for block hashes" taggedAs (UnitTest) in new TestSetup:
    // given
    val blockBodiesHashes: Seq[ByteString] = Seq(
      ByteString(Hex.decode("a218e2c611f21232d857e3c8cecdcdf1f65f25a4477f98f6f47e4063807f2308")),
      ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
    )

    val blockBodies: Seq[BlockBody] = Seq(baseBlockBody, baseBlockBody)

    blockchainWriter
      .storeBlockBody(BlockHash(blockBodiesHashes(0)), blockBodies(0))
      .and(blockchainWriter.storeBlockBody(BlockHash(blockBodiesHashes(1)), blockBodies(1)))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetBlockBodies(BigInt(0), blockBodiesHashes), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(ETHPackets.BlockBodies(BigInt(0), blockBodies), peerId)
    )

  // A reply is matched to the request by position, so skipping a block we lack would shift every later block's
  // body onto the wrong hash — the same "reply matched by position" risk receipts already guard against
  // (see "stop at the first block it has no receipts for" above). The old code used
  // `hashes.take(N).flatMap(getBlockBodyByHash)`, which silently DROPS a `None` and keeps going instead of
  // stopping (#18).
  it should "stop at the first block it has no body for, rather than skip it" taggedAs (UnitTest) in new TestSetup:
    val known1: ByteString = ByteString(Hex.decode("11" * 32))
    val unknownHash: ByteString = ByteString(Hex.decode("22" * 32))
    val known2: ByteString = ByteString(Hex.decode("33" * 32))

    // known2 is stored, but comes AFTER unknownHash in the request — it must never be reached, let alone have
    // its body land in unknownHash's response slot.
    blockchainWriter
      .storeBlockBody(BlockHash(known1), baseBlockBody)
      .and(blockchainWriter.storeBlockBody(BlockHash(known2), baseBlockBody))
      .commit()

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetBlockBodies(BigInt(1), Seq(known1, unknownHash, known2)), peerId)
    )

    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(ETHPackets.BlockBodies(BigInt(1), Seq(baseBlockBody)), peerId)
    )

  // ---- ETH68 GetBlockBodies size budget (#18) ---------------------------------------------------------------
  //
  // maxBlocksBodiesPerMessage caps the COUNT, but go-ethereum also stops adding bodies once the response reaches
  // softResponseLimit (2 MiB, eth/protocols/eth/handler.go's ServiceGetBlockBodiesQuery). fukuii had no byte
  // budget at all, so a batch of large bodies within the count cap could be served in one oversized reply.

  private def paddedBody(payloadSize: Int): BlockBody =
    val tx = BlockHelpers.defaultTx.copy(payload = ByteString(Array.fill[Byte](payloadSize)(0)))
    val stx = SignedTransaction.sign(tx, BlockHelpers.keyPair, None)
    BlockBody(List(stx), Nil)

  // go-ethereum's ServiceGetBlockBodiesQuery checks `bytes >= softResponseLimit` BEFORE adding the next body, using
  // only bytes already accumulated from PRIOR bodies — so the body that pushes the total past 2 MiB still ships,
  // and only a body requested AFTER the budget is already exhausted gets left out. Sizes chosen so body(1) is the
  // one that crosses the limit (comfortably under 2 MiB on its own, but cumulative with body(0) goes over) — it
  // must still be included; body(2), requested after the budget is exhausted, must not be.
  it should "cap a GetBlockBodies response at the 2 MiB soft limit, including the body that crosses it" taggedAs (
    UnitTest
  ) in new TestSetup:
    val hashes: Seq[ByteString] = Seq(
      ByteString(Hex.decode("44" * 32)),
      ByteString(Hex.decode("55" * 32)),
      ByteString(Hex.decode("66" * 32))
    )
    val bodies: Seq[BlockBody] = Seq(
      paddedBody(1500 * 1024), // ~1.5 MiB — well under budget alone
      paddedBody(1000 * 1024), // ~1.0 MiB — cumulative ~2.5 MiB: CROSSES the 2 MiB limit, must still be included
      paddedBody(500 * 1024) // budget already exhausted before this one is even measured — must be excluded
    )

    blockchainWriter
      .storeBlockBody(BlockHash(hashes(0)), bodies(0))
      .and(blockchainWriter.storeBlockBody(BlockHash(hashes(1)), bodies(1)))
      .and(blockchainWriter.storeBlockBody(BlockHash(hashes(2)), bodies(2)))
      .commit()

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetBlockBodies(BigInt(1), hashes), peerId)
    )

    val response = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    response.message.underlyingMsg match
      case ETHPackets.BlockBodies(reqId, returned) =>
        reqId shouldBe BigInt(1)
        returned shouldBe Seq(bodies(0), bodies(1))
      case other => fail(s"expected BlockBodies with the first 2 of the 3 requested bodies, got $other")

  // Liveness: a look-ahead budget check (`cumBytes + bodyBytes > limit` before deciding to include) would exclude
  // a body whose OWN size exceeds 2 MiB even as the very first candidate (cumBytes=0), so a peer asking for a
  // single block whose body alone is over 2 MiB would get an empty BlockBodies every time and could never fetch
  // that block from fukuii. go-ethereum's actual check only looks at bytes already accumulated from PRIOR
  // bodies (0 here), so the first body is always attempted regardless of its own size.
  it should "still serve a single body whose own size is over the 2 MiB soft limit" taggedAs (UnitTest) in new TestSetup:
    val hash: ByteString = ByteString(Hex.decode("77" * 32))
    val oversizedBody: BlockBody = paddedBody(3 * 1024 * 1024) // ~3 MiB, alone over the 2 MiB budget

    blockchainWriter.storeBlockBody(BlockHash(hash), oversizedBody).commit()

    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetBlockBodies(BigInt(1), Seq(hash)), peerId)
    )

    val response = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    response.message.underlyingMsg match
      case ETHPackets.BlockBodies(reqId, returned) =>
        reqId shouldBe BigInt(1)
        returned shouldBe Seq(oversizedBody)
      case other => fail(s"expected BlockBodies with the single oversized body, got $other")

  // ── eth/71 GetBlockAccessLists (EIP-8159) ─────────────────────────────────────────────────────────────────────────

  /** An entry's RLP, as it goes on the wire. */
  private def wireBytes(entry: RLPEncodeable): ByteString = ByteString(encode(entry))

  /** EIP-8159's entry for a list the server does not hold: the RLP empty string. */
  private val Unavailable: ByteString = ByteString(Array(0x80.toByte))

  /** A list of one account with one code change of `codeSize` bytes: an entry of about that size. */
  private def listOfSize(codeSize: Int): BlockAccessList =
    BlockAccessList(
      Seq(
        AccountChanges(
          Address(ByteString(Array.fill(20)(0x11.toByte))),
          Nil,
          Nil,
          Nil,
          Nil,
          Seq(CodeChange(1, ByteString(Array.fill(codeSize)(0x5b.toByte))))
        )
      )
    )

  // The acceptance case of #1428, and what hive's devp2p TestEth71GetBlockAccessLists checks of a served entry: the
  // requester hashes the RAW entry bytes it received (no decode and re-encode) against the header's
  // blockAccessListHash. A live Platåberget list, 65,994 bytes with a 64 KiB code change, so both it and that item
  // carry three-byte RLP length prefixes: the writer must give back exactly the bytes the reader took in.
  it should "serve a stored block access list byte for byte, and 0x80 for a block it holds none for, in order" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val live = PlatabergetBalVectors.load(PlatabergetBalVectors.Block275654)
    val unknown: ByteString = ByteString(Hex.decode("88" * 32))
    blockchainWriter.storeBlockAccessList(BlockHash(live.blockHash), live.accessList).commit()
    blockchainReader.getBlockAccessListByHash(BlockHash(live.blockHash)) shouldBe Some(live.bytes)
    live.bytes.length shouldBe 65994

    val (reply, wire) = requestBlockAccessLists(Seq(unknown, live.blockHash, unknown, live.blockHash))

    reply.requestId shouldBe BigInt(7)
    reply.entries.map(wireBytes) shouldBe Seq(Unavailable, live.bytes, Unavailable, live.bytes)
    // The message as sent carries the stored bytes verbatim.
    wire.containsSlice(live.bytes.toArray) shouldBe true
    // And as an eth/71 peer decodes it: the entry's raw bytes are the stored list and hash to the live header's
    // commitment.
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH71).fromBytes(Codes.BlockAccessListsCode, wire) match
      case Right(received: ETHPackets.BlockAccessLists) =>
        received.requestId shouldBe BigInt(7)
        received.entries.map(wireBytes) shouldBe Seq(Unavailable, live.bytes, Unavailable, live.bytes)
        ByteString(crypto.kec256(wireBytes(received.entries(1)).toArray)) shouldBe live.blockAccessListHash
      case other => fail(s"an eth/71 peer could not decode the reply: $other")

  it should "answer an empty request with an empty BlockAccessLists" taggedAs (UnitTest) in new TestSetup:
    val (reply, _) = requestBlockAccessLists(Nil)

    reply.requestId shouldBe BigInt(7)
    reply.entries shouldBe empty

  // go-ethereum serviceGetBlockAccessListsQuery: `if bytes >= softResponseLimit ... { break }` BEFORE each lookup,
  // counting only the lists served. The first list (1.5 MiB) leaves the total under 2 MiB, so the second (1 MiB) ships
  // and crosses it; the third is never looked at. The 0x80 in front counts for nothing.
  it should "stop at the 2 MiB soft limit after the list that crosses it, counting only served lists" taggedAs (
    UnitTest
  ) in new TestSetup:
    val lists: Seq[BlockAccessList] = Seq(1536 * 1024, 1024 * 1024, 512 * 1024).map(listOfSize)
    val hashes: Seq[ByteString] = Seq("a1", "a2", "a3").map(b => ByteString(Hex.decode(b * 32)))
    hashes.zip(lists).foreach((hash, list) => blockchainWriter.storeBlockAccessList(BlockHash(hash), list).commit())
    val unknown: ByteString = ByteString(Hex.decode("88" * 32))

    val (reply, _) = requestBlockAccessLists(unknown +: hashes)

    reply.entries.map(wireBytes) shouldBe Seq(Unavailable, lists(0).toBytes, lists(1).toBytes)

  // EIP-8159: "Responses containing a single BAL that exceeds 2 MiB are still valid, as the soft limit governs when to
  // stop appending additional items, not the maximum size of an individual item."
  it should "serve one list over 2 MiB on its own" taggedAs (UnitTest) in new TestSetup:
    val oversized = listOfSize(3 * 1024 * 1024)
    val small = listOfSize(16)
    val (bigHash, smallHash) = (ByteString(Hex.decode("b1" * 32)), ByteString(Hex.decode("b2" * 32)))
    blockchainWriter.storeBlockAccessList(BlockHash(bigHash), oversized).commit()
    blockchainWriter.storeBlockAccessList(BlockHash(smallHash), small).commit()

    val (reply, _) = requestBlockAccessLists(Seq(bigHash, smallHash))

    reply.entries.map(wireBytes) shouldBe Seq(oversized.toBytes)

  // go-ethereum maxBALsServe: at most 1,024 entries, unavailable ones included, whatever the request asks for.
  it should "answer at most 1,024 entries" taggedAs (UnitTest) in new TestSetup:
    val hashes: Seq[ByteString] = (0 until 1030).map(i => ByteString(Hex.decode(f"$i%064x")))

    val (reply, _) = requestBlockAccessLists(hashes)

    reply.entries.map(wireBytes) shouldBe Seq.fill(1024)(Unavailable)

  // The two limits share one guard, checked before every entry. The count limit counts entries, not lists: after 1,023
  // unavailable entries, a stored list as entry 1,024 is the last one served, and a stored list as entry 1,025 is cut
  // exactly as an unavailable entry there is (above). Both lists are tiny, so the byte limit plays no part.
  it should "cut at 1,024 entries whatever they hold: a list as entry 1,024 ships, a list as entry 1,025 does not" taggedAs (
    UnitTest
  ) in new TestSetup:
    val unknowns: Seq[ByteString] = (0 until 1023).map(i => ByteString(Hex.decode(f"$i%064x")))
    val (last, beyond) = (listOfSize(16), listOfSize(32))
    val (lastHash, beyondHash) = (ByteString(Hex.decode("e1" * 32)), ByteString(Hex.decode("e2" * 32)))
    blockchainWriter.storeBlockAccessList(BlockHash(lastHash), last).commit()
    blockchainWriter.storeBlockAccessList(BlockHash(beyondHash), beyond).commit()

    val (reply, _) = requestBlockAccessLists(unknowns ++ Seq(lastHash, beyondHash))

    reply.entries should have size 1024
    reply.entries.map(wireBytes) shouldBe (Seq.fill(1023)(Unavailable) :+ last.toBytes)

  // The byte limit counts only lists, and stops before the next entry whatever it would be. Unavailable entries ahead
  // of the crossing add nothing and ship; the list that crosses 2 MiB ships; the unavailable entry and the list after
  // it are both cut.
  it should "stop after the 2 MiB crossing with unavailable entries interleaved, cutting whatever follows" taggedAs (
    UnitTest
  ) in new TestSetup:
    val (first, crossing, after) = (listOfSize(1536 * 1024), listOfSize(1024 * 1024), listOfSize(16))
    val (firstHash, crossingHash, afterHash) =
      (ByteString(Hex.decode("f1" * 32)), ByteString(Hex.decode("f2" * 32)), ByteString(Hex.decode("f3" * 32)))
    Seq(firstHash -> first, crossingHash -> crossing, afterHash -> after).foreach((hash, list) =>
      blockchainWriter.storeBlockAccessList(BlockHash(hash), list).commit()
    )
    def unknown(i: Int): ByteString = ByteString(Hex.decode(f"$i%064x"))

    val (reply, _) = requestBlockAccessLists(
      Seq(unknown(1), firstHash, unknown(2), unknown(3), crossingHash, unknown(4), afterHash)
    )

    reply.entries.map(wireBytes) shouldBe
      Seq(Unavailable, first.toBytes, Unavailable, Unavailable, crossing.toBytes)

  // go-ethereum stops on `bytes >= softResponseLimit`: lists totalling exactly 2 MiB end the reply, even ahead of an
  // unavailable entry that would add nothing; one byte under, both the 0x80 and the next list still ship.
  it should "stop at exactly 2 MiB served, and not one byte under it" taggedAs (UnitTest) in new TestSetup:
    val Limit = 2 * 1024 * 1024
    // listOfSize(n) encodes to n + 46 bytes once every length takes a three-byte prefix.
    val (atLimit, underLimit, small) = (listOfSize(Limit - 46), listOfSize(Limit - 47), listOfSize(16))
    atLimit.toBytes.length shouldBe Limit
    underLimit.toBytes.length shouldBe Limit - 1
    val (atHash, underHash, smallHash) =
      (ByteString(Hex.decode("c1" * 32)), ByteString(Hex.decode("c2" * 32)), ByteString(Hex.decode("c3" * 32)))
    Seq(atHash -> atLimit, underHash -> underLimit, smallHash -> small).foreach((hash, list) =>
      blockchainWriter.storeBlockAccessList(BlockHash(hash), list).commit()
    )
    val unknown = ByteString(Hex.decode("88" * 32))

    val (atReply, _) = requestBlockAccessLists(Seq(atHash, unknown, smallHash))
    atReply.entries.map(wireBytes) shouldBe Seq(atLimit.toBytes)

    val (underReply, _) = requestBlockAccessLists(Seq(underHash, unknown, smallHash))
    underReply.entries.map(wireBytes) shouldBe Seq(underLimit.toBytes, Unavailable, small.toBytes)

  // Only a damaged store holds bytes the RLP reader rejects (every write is BlockAccessList.toBytes). Such an entry is
  // answered as unavailable, and logged at ERROR, rather than failing the reply and stopping the actor for every peer.
  it should "answer an unreadable stored list as unavailable, and keep serving" taggedAs (UnitTest) in new TestSetup:
    val (damagedHash, goodHash) = (ByteString(Hex.decode("d1" * 32)), ByteString(Hex.decode("d2" * 32)))
    val good = listOfSize(16)
    // A list header promising three bytes, followed by one.
    storagesInstance.storages.blockAccessListStorage
      .put(damagedHash, ByteString(Array(0xc3, 0x01).map(_.toByte)))
      .commit()
    blockchainWriter.storeBlockAccessList(BlockHash(goodHash), good).commit()

    val (reply, _) = requestBlockAccessLists(Seq(damagedHash, goodHash))
    reply.entries.map(wireBytes) shouldBe Seq(Unavailable, good.toBytes)

    val (next, _) = requestBlockAccessLists(Seq(goodHash))
    next.entries.map(wireBytes) shouldBe Seq(good.toBytes)

  it should "return block headers by block number" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(5))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 2, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block number when response is shorter then what was requested" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 3, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block number in reverse order" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(2))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(1))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 2, 0, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(5))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 2, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block hash when skipping headers" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(5))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(4))))
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(7))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(
        ETHPackets.GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), maxHeaders = 2, skip = 1, reverse = false),
        peerId
      )
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 2, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks and we are asking for blocks before genesis" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 3, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks ending at genesis" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(2))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 4, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader, blockchainReader.genesisHeader)),
        peerId
      )
    )

  it should "return evm code for hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val fakeEvmCode: ByteString = ByteString(Hex.decode("ffddaaffddaaffddaaffddaaffddaa"))
    val evmCodeHash: ByteString = ByteString(crypto.kec256(fakeEvmCode.toArray[Byte]))

    storagesInstance.storages.evmCodeStorage.put(evmCodeHash, fakeEvmCode).commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(MessageFromPeer(GetNodeData(Seq(evmCodeHash)), peerId))

    // then
    networkPeerManager.expectMsg(NetworkPeerManagerActor.SendMessageCmd(NodeData(Seq(fakeEvmCode)), peerId))

  it should "return mptNode for hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val exampleNibbles: ByteString = ByteString(HexPrefix.bytesToNibbles(Hex.decode("ffddaa")))
    val exampleHash: ByteString = ByteString(Hex.decode("ab" * 32))
    val extensionNode: MptNode = ExtensionNode(exampleNibbles, HashNode(exampleHash.toArray[Byte]))

    storagesInstance.storages.stateStorage.saveNode(
      ByteString(extensionNode.hash),
      extensionNode.toBytes: Array[Byte],
      0
    )

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetNodeData(Seq(ByteString(extensionNode.hash))), peerId)
    )

    // then
    networkPeerManager.expectMsg(NetworkPeerManagerActor.SendMessageCmd(NodeData(Seq(extensionNode.toBytes)), peerId))

  trait TestSetup extends EphemBlockchainTestSetup:
    implicit override lazy val classicSystem: ActorSystem = ActorSystem("BlockchainHostActor_System")

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    val peerConf: PeerConfiguration = new PeerConfiguration:
      override val fastSyncHostConfiguration: FastSyncHostConfiguration = new FastSyncHostConfiguration:
        val maxBlocksHeadersPerMessage: Int = 200
        val maxBlocksBodiesPerMessage: Int = 200
        val maxReceiptsPerMessage: Int = 200
        val maxMptComponentsPerMessage: Int = 200
      override val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration:
        override val waitForTcpAckTimeout: FiniteDuration = Timeouts.normalTimeout
        override val waitForHandshakeTimeout: FiniteDuration = Timeouts.normalTimeout
      override val waitForHelloTimeout: FiniteDuration = 30 seconds
      override val waitForStatusTimeout: FiniteDuration = 30 seconds
      override val waitForChainCheckTimeout: FiniteDuration = 15 seconds
      override val connectMaxRetries: Int = 3
      override val connectRetryDelay: FiniteDuration = 1 second
      override val disconnectPoisonPillTimeout: FiniteDuration = 5 seconds
      override val minOutgoingPeers = 5
      override val maxOutgoingPeers = 10
      override val maxIncomingPeers = 5
      override val maxPendingPeers = 5
      override val pruneIncomingPeers = 0
      override val minPruneAge: FiniteDuration = 1.minute
      override val networkId: Long = 1L
      override val p2pVersion: Int = Config.Network.peer.p2pVersion

      override val updateNodesInitialDelay: FiniteDuration = 5.seconds
      override val updateNodesInterval: FiniteDuration = 20.seconds
      override val shortBlacklistDuration: FiniteDuration = 1.minute
      override val longBlacklistDuration: FiniteDuration = 3.minutes
      override val statSlotDuration: FiniteDuration = 1.minute
      override val statSlotCount: Int = 30

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header
    val baseBlockBody: BlockBody = BlockBody(Nil, Nil)

    val peerId: PeerId = PeerId("1")

    val peerEventBus: TestProbe = TestProbe()
    val networkPeerManager: TestProbe = TestProbe()
    val pendingTxManager: TestProbe = TestProbe()

    val blockchainHost: org.apache.pekko.actor.typed.ActorRef[BlockchainHostActor.Command] =
      classicSystem.spawn(
        BlockchainHostActor(
          blockchainReader,
          storagesInstance.storages.evmCodeStorage,
          peerConf,
          peerEventBus.ref,
          networkPeerManager.ref,
          pendingTxManager.ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command]
        ),
        s"blockchain-host-${System.nanoTime()}"
      )

    /** Sends `GetBlockAccessLists(7, hashes)` and returns the `BlockAccessLists` reply with its wire payload. */
    def requestBlockAccessLists(hashes: Seq[ByteString]): (ETHPackets.BlockAccessLists, Array[Byte]) =
      blockchainHost ! BlockchainHostActor.PeerEventReceived(
        MessageFromPeer(ETHPackets.GetBlockAccessLists(BigInt(7), hashes), peerId)
      )
      val sent = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd].message
      sent.underlyingMsg match
        case reply: ETHPackets.BlockAccessLists => (reply, sent.toBytes)
        case other                              => fail(s"expected BlockAccessLists, got $other")
