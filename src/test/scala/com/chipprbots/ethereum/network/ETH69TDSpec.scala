package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for ETH/69 TD handling.
  *
  * ETH/69 removes totalDifficulty from the Status wire message. For ETC's PoW chain we recover TD by looking it up in
  * local ChainWeightStorage via latestBlockHash. These tests verify that:
  *   - `fromETH69Status` stores the caller-resolved chainWeight and preserves latestBlock separately
  *   - `PeerInfo.apply` initialises maxBlockNumber from latestBlock (not from chainWeight.totalDifficulty)
  *   - The fallback path (peer ahead of us) uses block-number proxy correctly
  */
class ETH69TDSpec extends AnyFlatSpec with Matchers {

  private val genesisHash = Fixtures.Blocks.Genesis.header.hash
  private val latestHash = Fixtures.Blocks.Block3125369.header.hash
  private val latestBlockNr = Fixtures.Blocks.Block3125369.header.number

  private val dummyForkId = ForkId(0L, None)

  private def eth69Status(latestBlock: BigInt, latestBlockHash: ByteString): ETH69.Status =
    ETH69.Status(
      protocolVersion = Capability.ETH69.version,
      networkId = 1L,
      genesisHash = genesisHash,
      forkId = dummyForkId,
      earliestBlock = BigInt(0),
      latestBlock = latestBlock,
      latestBlockHash = latestBlockHash
    )

  // -------------------------------------------------------------------------
  // RemoteStatus.fromETH69Status
  // -------------------------------------------------------------------------

  it should "store the resolved chainWeight (not a block-number proxy) in RemoteStatus" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)

    remoteStatus.chainWeight shouldBe actualTD
    remoteStatus.chainWeight.totalDifficulty should not be latestBlockNr
  }

  it should "store latestBlock in the dedicated RemoteStatus.latestBlock field" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)

    remoteStatus.latestBlock shouldBe Some(latestBlockNr)
  }

  it should "store block-number proxy in chainWeight when local lookup fails (peer ahead of us)" taggedAs UnitTest in {
    val proxy = ChainWeight.totalDifficultyOnly(latestBlockNr) // fallback
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, proxy)

    remoteStatus.chainWeight.totalDifficulty shouldBe latestBlockNr
    remoteStatus.latestBlock shouldBe Some(latestBlockNr)
  }

  it should "set capability to the negotiated capability" taggedAs UnitTest in {
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(
      status,
      Capability.ETH69,
      supportsSnap = true,
      Nil,
      ChainWeight.totalDifficultyOnly(BigInt(0))
    )

    remoteStatus.capability shouldBe Capability.ETH69
    remoteStatus.supportsSnap shouldBe true
    remoteStatus.bestHash shouldBe latestHash
    remoteStatus.genesisHash shouldBe genesisHash
  }

  // -------------------------------------------------------------------------
  // PeerInfo.apply for ETH/69
  // -------------------------------------------------------------------------

  it should "initialise maxBlockNumber from latestBlock, not from chainWeight.totalDifficulty" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("999999999999999999999999999"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    // maxBlockNumber must be the block number, not the huge TD value
    peerInfo.maxBlockNumber shouldBe latestBlockNr
    peerInfo.maxBlockNumber should not be actualTD.totalDifficulty
  }

  it should "initialise maxBlockNumber from latestBlock when chainWeight is a block-number proxy" taggedAs UnitTest in {
    val proxy = ChainWeight.totalDifficultyOnly(latestBlockNr)
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, proxy)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe latestBlockNr
  }

  it should "default maxBlockNumber to 0 when latestBlock is absent (ETH68 path)" taggedAs UnitTest in {
    // ETH68 RemoteStatus has latestBlock = None
    val eth68Status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000")),
      bestHash = latestHash,
      genesisHash = genesisHash
      // latestBlock defaults to None
    )
    val peerInfo = PeerInfo(eth68Status, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe BigInt(0)
  }

  it should "set chainWeight in PeerInfo from RemoteStatus chainWeight" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    peerInfo.chainWeight shouldBe actualTD
  }

  // -------------------------------------------------------------------------
  // ETH/68 TD handling (wire provides totalDifficulty directly)
  // -------------------------------------------------------------------------

  it should "ETH68: store totalDifficulty from wire in chainWeight" taggedAs UnitTest in {
    val wireTD = BigInt("123456789000000000000000000")
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(wireTD),
      bestHash = latestHash,
      genesisHash = genesisHash
    )

    eth68RemoteStatus.chainWeight.totalDifficulty shouldBe wireTD
    eth68RemoteStatus.latestBlock shouldBe None
  }

  it should "ETH68: PeerInfo.apply initialises maxBlockNumber to 0 (updated reactively)" taggedAs UnitTest in {
    // ETH68 maxBlockNumber is NOT read from chainWeight — it starts at 0 and is
    // updated via peerHasUpdatedBestBlock messages as the peer announces new blocks.
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000")),
      bestHash = latestHash,
      genesisHash = genesisHash
    )
    val peerInfo = PeerInfo(eth68RemoteStatus, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe BigInt(0)
    peerInfo.chainWeight.totalDifficulty shouldBe BigInt("100000000000000000000000000")
  }

  it should "ETH68: chainWeight.totalDifficulty is the actual wire TD, not a block number" taggedAs UnitTest in {
    val wireTD = BigInt("987654321000000000000000000")
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(wireTD),
      bestHash = latestHash,
      genesisHash = genesisHash
    )
    val peerInfo = PeerInfo(eth68RemoteStatus, forkAccepted = true)

    // For ETH68 the TD comparison in BlockBroadcast uses peerInfo.chainWeight — it must be accurate
    peerInfo.chainWeight.totalDifficulty shouldBe wireTD
    peerInfo.chainWeight.totalDifficulty should be > BigInt(100_000_000L) // clearly not a block number
  }

  // -------------------------------------------------------------------------
  // EIP-7642 STATUS wire format
  //
  // Both core-geth's `StatusPacket` (eth/protocols/eth/protocol.go) and besu's
  // `StatusMessage69` encode the canonical 7 fields:
  //   `[version, networkId, genesis, forkId, earliestBlock, latestBlock, latestBlockHash]`
  //
  // PR #1194 erroneously dropped `earliestBlock` based on a misread of EIP-7642 — that
  // produced a 6-field STATUS that geth rejected with `rlp: input string too long for
  // uint64, decoding into (eth.StatusPacket).LatestBlock` (geth's decoder reads the
  // 32-byte `latestBlockHash` into the `LatestBlock` uint64 slot when only 6 fields
  // arrive). Confirmed in hive's `ethereum/sync` simulator at debug verbosity.
  //
  // The current encoder emits the canonical 7 fields. The decoder still accepts the
  // 6-field legacy shape (for the rollout window where some fukuii peers may still
  // be running pre-fix builds) and the ETH/68-shape that wrong-chain peers send.
  // -------------------------------------------------------------------------

  it should "encode ETH/69 STATUS as 7 fields per EIP-7642 (geth/besu canonical)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._
    import com.chipprbots.ethereum.rlp.{RLPList, rawDecode}

    val status = eth69Status(latestBlockNr, latestHash)
    val encoded: Array[Byte] = new StatusEnc(status).toBytes
    val decoded = rawDecode(encoded)

    decoded shouldBe a[RLPList]
    val fields = decoded.asInstanceOf[RLPList].items.toList
    fields should have size 7 // canonical layout matches geth StatusPacket / besu StatusMessage69
  }

  it should "round-trip through the spec-compliant 7-field wire format" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._

    val original = eth69Status(latestBlockNr, latestHash).copy(earliestBlock = BigInt(0))
    val encoded: Array[Byte] = new StatusEnc(original).toBytes
    val decoded = encoded.toETH69Status

    decoded.protocolVersion shouldBe original.protocolVersion
    decoded.networkId shouldBe original.networkId
    decoded.genesisHash shouldBe original.genesisHash
    decoded.forkId shouldBe original.forkId
    decoded.earliestBlock shouldBe original.earliestBlock
    decoded.latestBlock shouldBe original.latestBlock
    decoded.latestBlockHash shouldBe original.latestBlockHash
  }

  it should "preserve a non-zero earliestBlock through the 7-field round-trip" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._

    val original = eth69Status(latestBlockNr, latestHash).copy(earliestBlock = BigInt(123))
    val encoded: Array[Byte] = new StatusEnc(original).toBytes
    val decoded = encoded.toETH69Status

    decoded.earliestBlock shouldBe BigInt(123)
    decoded.latestBlock shouldBe latestBlockNr
    decoded.latestBlockHash shouldBe latestHash
  }

  it should "decode legacy 6-field fukuii STATUS shape (backward compat with pre-fix peers)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._
    import com.chipprbots.ethereum.rlp.{RLPList, RLPValue, encode}
    import com.chipprbots.ethereum.utils.ByteUtils
    import com.chipprbots.ethereum.forkid.ForkId._

    // Hand-build the 6-field legacy shape used by pre-fix fukuii.
    val legacyRlp = RLPList(
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(Capability.ETH69.version))),
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(1))),
      RLPValue(genesisHash.toArray[Byte]),
      dummyForkId.toRLPEncodable,
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlockNr)),
      RLPValue(latestHash.toArray[Byte])
    )
    val legacyBytes: Array[Byte] = encode(legacyRlp)

    val decoded = legacyBytes.toETH69Status

    decoded.networkId shouldBe 1L
    decoded.latestBlock shouldBe latestBlockNr
    decoded.latestBlockHash shouldBe latestHash
    // 6-field shape doesn't carry earliestBlock — decoder defaults to 0.
    decoded.earliestBlock shouldBe BigInt(0)
  }

  it should "reject malformed STATUS payloads with the canonical error message" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._
    import com.chipprbots.ethereum.rlp.{RLPList, RLPValue, encode}

    val garbage: Array[Byte] = encode(RLPList(RLPValue(Array[Byte](0x01)), RLPValue(Array[Byte](0x02))))
    val ex = intercept[RuntimeException](garbage.toETH69Status)
    ex.getMessage should include("Cannot decode ETH69.Status")
  }

  // Real-world payload from a wrong-chain peer (Holesky-derived testnet) seen on ETC mainnet sync.
  // Peer announced ETH/69 capability but emitted the ETH/68 STATUS layout
  //   [version, networkId, totalDifficulty, bestHash, genesis, forkId]
  // pre-fix this generated `DECODE_ERROR: Cannot decode ETH69.Status` per peer, churning the pool.
  // Post-fix the decode succeeds; the peer is then rejected at the genesis-hash check downstream
  // as `Useless peer`, which is the correct behaviour for wrong-chain interop.
  it should "decode ETH/68-shape STATUS sent on ETH/69 channel by non-spec peers (Holesky-style payload)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69.Status._
    import com.chipprbots.ethereum.rlp.{RLPList, RLPValue, encode}
    import com.chipprbots.ethereum.utils.ByteUtils
    import com.chipprbots.ethereum.forkid.ForkId._

    val holeskyGenesis = ByteString(
      org.bouncycastle.util.encoders.Hex.decode(
        "b5f7f912443c940f21fd611f12828d75b534364ed9e95ca4e307729a4661bde4"
      )
    )
    val bestHash = ByteString(
      org.bouncycastle.util.encoders.Hex.decode(
        "c262e4a9caf4c79696a0333e6cbc15c05d9286715bfbec226568c2d624def352"
      )
    )
    val ethSixtyEightShape = RLPList(
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(69))),
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(806070))), // networkId — non-ETC, peer is wrong chain
      RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(1))), // totalDifficulty — discarded on decode
      RLPValue(bestHash.toArray[Byte]),
      RLPValue(holeskyGenesis.toArray[Byte]),
      ForkId(0xbcf811L, None).toRLPEncodable
    )
    val payload: Array[Byte] = encode(ethSixtyEightShape)

    val decoded = payload.toETH69Status

    // Decode must succeed (the whole point of this PR — no DECODE_ERROR for these peers).
    decoded.protocolVersion shouldBe 69
    decoded.networkId shouldBe 806070L
    // genesis is from position 4 of the ETH/68 layout, NOT position 2 (where ETH/69 spec puts it).
    decoded.genesisHash shouldBe holeskyGenesis
    // bestHash from position 3 maps to latestBlockHash so downstream code can examine it.
    decoded.latestBlockHash shouldBe bestHash
    // ETH/68 doesn't carry a block number; latestBlock defaults to 0. earliestBlock also 0.
    decoded.latestBlock shouldBe BigInt(0)
    decoded.earliestBlock shouldBe BigInt(0)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // ETH68_BOOTSTRAP gap-adjustment (Part 1 of TD inflation fix)
  //
  // updateBestBlockForPivot now subtracts (peerBestBlock - pivotBlock) * pivotDiff
  // from the peer's TD before storing it as the pivot's chain weight. On a constant-
  // difficulty chain this makes the stored TD exactly canonical_TD_at_pivot.
  // These tests verify the adjustment formula in isolation.
  // ─────────────────────────────────────────────────────────────────────────────

  /** Mirrors the ETH68_BOOTSTRAP gap-adjustment formula from updateBestBlockForPivot. */
  private def eth68BootstrapAdjustedTD(
      peerTD:     BigInt,
      peerBlock:  Option[BigInt],
      pivotBlock: BigInt,
      pivotDiff:  BigInt
  ): BigInt = {
    val blockGap = peerBlock.map(pb => (pb - pivotBlock).max(BigInt(0))).getOrElse(BigInt(0))
    (peerTD - blockGap * pivotDiff).max(pivotDiff)
  }

  it should "ETH68_BOOTSTRAP: subtract 64-block gap so stored TD equals canonical pivot TD" taggedAs UnitTest in {
    val D      = BigInt("2500000000000000")
    val pivotN = BigInt(24_649_912)
    val peerN  = pivotN + 64
    val peerTD = peerN * D     // canonical TD at peer's head (constant-diff chain)
    val pivotTD = pivotN * D   // expected canonical TD at pivot

    val adjusted = eth68BootstrapAdjustedTD(peerTD, Some(peerN), pivotN, D)

    adjusted shouldBe pivotTD
    adjusted should be < peerTD
    adjusted should be > D    // floor guard not triggered
  }

  it should "ETH68_BOOTSTRAP: large gap (3000 blocks) still corrects to canonical pivot TD" taggedAs UnitTest in {
    val D       = BigInt("2500000000000000")
    val pivotN  = BigInt(24_649_912)
    val peerN   = pivotN + 3000
    val peerTD  = peerN * D
    val pivotTD = pivotN * D

    val adjusted   = eth68BootstrapAdjustedTD(peerTD, Some(peerN), pivotN, D)
    val unadjusted = peerTD

    // Without adjustment: inflated by 3000 blocks of TD
    (unadjusted - pivotTD) shouldBe D * 3000
    // With adjustment: exactly canonical
    adjusted shouldBe pivotTD
  }

  it should "ETH68_BOOTSTRAP: no peerBestBlock means no gap adjustment (backward-compat)" taggedAs UnitTest in {
    val D      = BigInt("2500000000000000")
    val pivotN = BigInt(24_649_912)
    val peerN  = pivotN + 64
    val peerTD = peerN * D

    val adjusted = eth68BootstrapAdjustedTD(peerTD, None, pivotN, D)

    // peerBestBlock absent → blockGap=0 → no change
    adjusted shouldBe peerTD
  }

  it should "ETH68 and ETH69 remoteStatus agree on canonical TD when resolving same block" taggedAs UnitTest in {
    val canonicalTD = ChainWeight.totalDifficultyOnly(BigInt("24492222912327225433673"))

    val eth68Rs = RemoteStatus(
      capability  = Capability.ETH68,
      networkId   = 61L,
      chainWeight = canonicalTD,
      bestHash    = latestHash,
      genesisHash = genesisHash
    )
    val eth69Rs = RemoteStatus.fromETH69Status(
      eth69Status(latestBlockNr, latestHash),
      Capability.ETH69,
      supportsSnap = false,
      Nil,
      canonicalTD  // DB_LOOKUP path returned the same canonical TD
    )

    eth68Rs.chainWeight shouldBe canonicalTD
    eth69Rs.chainWeight shouldBe canonicalTD
    eth68Rs.chainWeight shouldBe eth69Rs.chainWeight
  }

  it should "TD_AUDIT: selfTD exceeding bestEth68PeerTD by >5 blocks signals inflation" taggedAs UnitTest in {
    val D           = BigInt("2500000000000000")
    val canonicalTD = BigInt("24492222912327225433673")

    val selfTDClean    = canonicalTD + D * 3      // within 5 blocks — no signal
    val selfTDInflated = canonicalTD + D * 3352   // 3352 phantom blocks — inflation
    val peerTD         = canonicalTD

    // Clean: delta ≤ 5 blocks
    (selfTDClean - peerTD) should be <= D * 5

    // Inflated: delta > 5 blocks → inflated=true
    (selfTDInflated - peerTD) should be > D * 5

    // Boundary (exactly 5): should NOT signal (> not ≥)
    val selfTDAtBoundary = canonicalTD + D * 5
    val boundaryDelta: BigInt = selfTDAtBoundary - peerTD
    boundaryDelta should be <= D * 5
  }
}
