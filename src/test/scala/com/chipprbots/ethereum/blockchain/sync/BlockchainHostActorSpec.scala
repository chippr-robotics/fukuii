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
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.LegacyReceipt
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
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.rlp.RLPList
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
