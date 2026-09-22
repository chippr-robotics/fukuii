package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm

/** A block timestamp is a `uint64` (yellow paper `Hs`, execution-apis `Quantity`, go-ethereum `Header.Time uint64`).
  * `Timestamp` is an `opaque type Timestamp = Long`, and a JVM `Long` is signed, so every timestamp at or above 2^63
  * reads as a negative number.
  *
  * Measured consequence (hive `ethereum/eels/consume-engine`, commit c82c89e): 32 EIP-4788 tests failed with
  * `JSONRPCError(code=-38005, "newPayloadV3 cannot be used pre-Cancun")`, every one of them carrying a timestamp of
  * 2^64-1 or 2^64-2 — the two values the EELS fixtures use precisely to probe this boundary.
  *
  * The tests below fix the three distinct manifestations:
  *   - ordering (`Timestamp.>` and friends, plus `Ordering[Timestamp]`),
  *   - fork-gate dispatch (`BlockchainConfig.is*Timestamp` and the EVM-side duplicates),
  *   - the `Long -> UInt256 / BigInt` widening that feeds the EIP-4788 ring-buffer index.
  *
  * Every case is paired with a control at an ordinary timestamp, because the whole safety argument for this change is
  * that it is provably inert below 2^63.
  */
class TimestampUnsignedSpec extends AnyFlatSpec with Matchers:

  /** 2^64-1 — the maximum uint64, stored as the bit pattern `0xFFFFFFFFFFFFFFFF` (signed reading: -1). */
  private val MaxUint64: Timestamp = Timestamp(-1L)

  /** 2^64-2 (signed reading: -2). */
  private val MaxUint64Minus1: Timestamp = Timestamp(-2L)

  private val MaxUint64BigInt: BigInt = (BigInt(1) << 64) - 1
  private val MaxUint64Minus1BigInt: BigInt = (BigInt(1) << 64) - 2

  /** EIP-4788 `HISTORY_BUFFER_LENGTH`. */
  private val HistoryBufferLength = 8191

  // ---------------------------------------------------------------------------------------------
  // 1. Ordering
  // ---------------------------------------------------------------------------------------------

  behavior of "Timestamp ordering"

  it should "treat 2^64-1 as GREATER than an ordinary timestamp" taggedAs (UnitTest) in {
    val ordinary = Timestamp(1000L)
    (MaxUint64 > ordinary) shouldBe true
    (MaxUint64 >= ordinary) shouldBe true
    (ordinary < MaxUint64) shouldBe true
    (ordinary <= MaxUint64) shouldBe true
    (MaxUint64 < ordinary) shouldBe false
  }

  it should "order 2^64-2 below 2^64-1 but above every ordinary timestamp" taggedAs (UnitTest) in {
    (MaxUint64Minus1 < MaxUint64) shouldBe true
    (MaxUint64Minus1 > Timestamp(1000L)) shouldBe true
    (MaxUint64Minus1 > Timestamp(Long.MaxValue)) shouldBe true
  }

  it should "pick the uint64 maximum from min/max" taggedAs (UnitTest) in {
    MaxUint64.max(Timestamp(1000L)) shouldBe MaxUint64
    MaxUint64.min(Timestamp(1000L)) shouldBe Timestamp(1000L)
  }

  it should "expose an Ordering consistent with the relational operators" taggedAs (UnitTest) in {
    val ord = summon[Ordering[Timestamp]]
    ord.compare(MaxUint64, Timestamp(1000L)) should be > 0
    ord.max(Timestamp(1000L), MaxUint64) shouldBe MaxUint64
    Seq(MaxUint64, Timestamp(1000L), Timestamp(0L)).max shouldBe MaxUint64
  }

  it should "report MaxValue as the true uint64 maximum" taggedAs (UnitTest) in {
    // 2^63-1 is NOT the largest timestamp; 2^64-1 is.
    (Timestamp.MaxValue >= MaxUint64) shouldBe true
    (Timestamp.MaxValue >= Timestamp(Long.MaxValue)) shouldBe true
  }

  // CONTROL: below 2^63 nothing changes.
  it should "be unchanged at ordinary timestamps (control)" taggedAs (UnitTest) in {
    val probes = Seq(0L, 1L, 10L, 1000000000L, 1L << 62, Long.MaxValue)
    for a <- probes; b <- probes do
      withClue(s"a=$a b=$b: ") {
        (Timestamp(a) > Timestamp(b)) shouldBe (a > b)
        (Timestamp(a) >= Timestamp(b)) shouldBe (a >= b)
        (Timestamp(a) < Timestamp(b)) shouldBe (a < b)
        (Timestamp(a) <= Timestamp(b)) shouldBe (a <= b)
        Timestamp(a).min(Timestamp(b)).toLong shouldBe math.min(a, b)
        Timestamp(a).max(Timestamp(b)).toLong shouldBe math.max(a, b)
      }
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Fork gates
  // ---------------------------------------------------------------------------------------------

  behavior of "BlockchainConfig timestamp fork gates"

  private val baseConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  private def configWithAllForksAt(ts: Option[Long]): BlockchainConfig =
    baseConfig.copy(forkTimestamps =
      ForkTimestamps(
        shanghaiTimestamp = ts,
        cancunTimestamp = ts,
        pragueTimestamp = ts,
        osakaTimestamp = ts,
        amsterdamTimestamp = ts,
        bpo1Timestamp = ts,
        bpo2Timestamp = ts
      )
    )

  it should "report Cancun ACTIVE at timestamp 2^64-1 when cancunTimestamp = Some(0)" taggedAs (UnitTest) in {
    // THE MEASURED DEFECT. EngineApiController builds `isCancunPayload` from this call; with it
    // false and every Cancun field present, newPayloadV3 returns -38005.
    val config = configWithAllForksAt(Some(0L))
    config.isCancunTimestamp(MaxUint64) shouldBe true
    config.isShanghaiTimestamp(MaxUint64) shouldBe true
    config.isPragueTimestamp(MaxUint64) shouldBe true
    config.isOsakaTimestamp(MaxUint64) shouldBe true
    config.isAmsterdamTimestamp(MaxUint64) shouldBe true
    config.isBpo1Timestamp(MaxUint64) shouldBe true
    config.isBpo2Timestamp(MaxUint64) shouldBe true
  }

  it should "report Cancun ACTIVE at timestamp 2^64-2" taggedAs (UnitTest) in {
    configWithAllForksAt(Some(0L)).isCancunTimestamp(MaxUint64Minus1) shouldBe true
  }

  it should "keep every gate INACTIVE when the fork timestamp is absent (ETC safety)" taggedAs (UnitTest) in {
    // ETC/Mordor/Gorgoroth safety rests on `Option.exists` never evaluating its predicate.
    // This must hold at EVERY timestamp, including the top half of the uint64 range.
    val config = configWithAllForksAt(None)
    for ts <- Seq(Timestamp(0L), Timestamp(1L), Timestamp(1700000000L), Timestamp(Long.MaxValue), MaxUint64) do
      withClue(s"ts=${ts.toLong}: ") {
        config.isShanghaiTimestamp(ts) shouldBe false
        config.isCancunTimestamp(ts) shouldBe false
        config.isPragueTimestamp(ts) shouldBe false
        config.isOsakaTimestamp(ts) shouldBe false
        config.isAmsterdamTimestamp(ts) shouldBe false
        config.isBpo1Timestamp(ts) shouldBe false
        config.isBpo2Timestamp(ts) shouldBe false
      }
  }

  // CONTROL: ordinary activation values behave exactly as before.
  it should "be unchanged at ordinary activation timestamps (control)" taggedAs (UnitTest) in {
    val config = configWithAllForksAt(Some(1710338135L)) // ETH mainnet Cancun
    config.isCancunTimestamp(Timestamp(1710338134L)) shouldBe false
    config.isCancunTimestamp(Timestamp(1710338135L)) shouldBe true
    config.isCancunTimestamp(Timestamp(1710338136L)) shouldBe true
    config.isCancunTimestamp(Timestamp(0L)) shouldBe false
    config.isCancunTimestamp(Timestamp(1L << 62)) shouldBe true
  }

  behavior of "BlockchainConfigForEvm timestamp fork gates"

  private def evmConfigWith(ts: Option[Long]): BlockchainConfigForEvm =
    BlockchainConfigForEvm(configWithAllForksAt(ts))

  it should "report every EVM-side gate ACTIVE at 2^64-1 when activation is 0" taggedAs (UnitTest) in {
    val cfg = evmConfigWith(Some(0L))
    cfg.isPragueTimestamp(MaxUint64) shouldBe true
    cfg.isOsakaTimestamp(MaxUint64) shouldBe true
    cfg.isBpo1Timestamp(MaxUint64) shouldBe true
    cfg.isBpo2Timestamp(MaxUint64) shouldBe true
  }

  it should "keep every EVM-side gate INACTIVE when unset, at any timestamp (control)" taggedAs (UnitTest) in {
    val cfg = evmConfigWith(None)
    for ts <- Seq(Timestamp(0L), Timestamp(1700000000L), MaxUint64) do
      cfg.isPragueTimestamp(ts) shouldBe false
      cfg.isOsakaTimestamp(ts) shouldBe false
      cfg.isBpo1Timestamp(ts) shouldBe false
      cfg.isBpo2Timestamp(ts) shouldBe false
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Unsigned widening — EIP-4788 ring-buffer index
  // ---------------------------------------------------------------------------------------------

  behavior of "Timestamp unsigned widening"

  it should "widen 2^64-1 to the uint64 value, not to a sign-extended 2^256-1" taggedAs (UnitTest) in {
    MaxUint64.toUnsignedBigInt shouldBe MaxUint64BigInt
    MaxUint64Minus1.toUnsignedBigInt shouldBe MaxUint64Minus1BigInt
    MaxUint64.toUInt256.toBigInt shouldBe MaxUint64BigInt
  }

  it should "produce EIP-4788 ring index 4095 for timestamp 2^64-1" taggedAs (UnitTest) in {
    // 8191 = 2^13-1, so 2^13 == 1 (mod 8191). 64 = 13*4+12 gives 2^64 == 2^12 = 4096,
    // hence (2^64-1) mod 8191 = 4095. A sign-extended 2^256-1 gives 511 instead
    // (256 = 13*19+9 -> 2^256 == 2^9 = 512), which is the wrong storage slot.
    MaxUint64.toUInt256.mod(UInt256(HistoryBufferLength)).toBigInt shouldBe BigInt(4095)
    MaxUint64Minus1.toUInt256.mod(UInt256(HistoryBufferLength)).toBigInt shouldBe BigInt(4094)

    // cross-check the arithmetic against the sign-extended value this replaces
    (MaxUint64BigInt % HistoryBufferLength) shouldBe BigInt(4095)
    (((BigInt(1) << 256) - 1) % HistoryBufferLength) shouldBe BigInt(511)
  }

  it should "store the uint64 timestamp value, not a sign-extended one" taggedAs (UnitTest) in {
    // EIP-4788 stores the timestamp itself at `timestamp % HISTORY_BUFFER_LENGTH`;
    // test_beacon_root_equal_to_timestamp reads that word back and compares it.
    MaxUint64.toUInt256.toBigInt shouldBe MaxUint64BigInt
  }

  // CONTROL: widening is the identity below 2^63.
  it should "widen ordinary timestamps identically (control)" taggedAs (UnitTest) in {
    for t <- Seq(0L, 1L, 12L, 1700000000L, 1L << 62, Long.MaxValue) do
      withClue(s"t=$t: ") {
        Timestamp(t).toUnsignedBigInt shouldBe BigInt(t)
        Timestamp(t).toUInt256 shouldBe UInt256(BigInt(t))
        Timestamp(t).toUInt256.mod(UInt256(HistoryBufferLength)).toBigInt shouldBe (BigInt(t) % HistoryBufferLength)
      }
  }

  // ---------------------------------------------------------------------------------------------
  // 4. RLP / block hash
  // ---------------------------------------------------------------------------------------------

  behavior of "Timestamp RLP encoding"

  it should "encode 2^64-1 as eight 0xFF bytes, not one" taggedAs (UnitTest) in {
    // BlockHeader.hash = kec256(toBytes); a 1-byte encoding of 2^64-1 (the minimal
    // two's-complement form of -1) hashes the header as if the timestamp were 255,
    // so the computed block hash never matches the payload's.
    import com.chipprbots.ethereum.rlp.*
    val encoded = encode(Timestamp.rlpCodec.encode(MaxUint64))
    encoded shouldBe Array[Byte](0x88.toByte) ++ Array.fill(8)(0xff.toByte)
    Timestamp.rlpCodec.decode(rawDecode(encoded)) shouldBe MaxUint64
  }

  it should "encode ordinary timestamps identically (control)" taggedAs (UnitTest) in {
    import com.chipprbots.ethereum.rlp.*
    for t <- Seq(0L, 1L, 127L, 128L, 255L, 1700000000L, Long.MaxValue) do
      withClue(s"t=$t: ") {
        val encoded = encode(Timestamp.rlpCodec.encode(Timestamp(t)))
        encoded shouldBe encode(RLPImplicits.bigIntEncDec.encode(BigInt(t)))
        Timestamp.rlpCodec.decode(rawDecode(encoded)) shouldBe Timestamp(t)
      }
  }
