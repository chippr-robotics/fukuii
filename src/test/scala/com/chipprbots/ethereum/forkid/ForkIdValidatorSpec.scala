package com.chipprbots.ethereum.forkid

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.utils.Config.*

import ForkIdValidationResult.*
import ForkIdValidator.*

class ForkIdValidatorSpec extends AnyWordSpec with Matchers:

  implicit val runtime: IORuntime = IORuntime.global

  val config = blockchains

  val ethGenesisHash: ByteString = ByteString(
    Hex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3")
  )

  "ForkIdValidator" must {
    "correctly validate ETH peers" in {
      // latest fork at the time of writing those assertions (in the spec) was Petersburg
      val ethForksList: List[BigInt] = List(1150000, 1920000, 2463000, 2675000, 4370000, 7280000)

      def validatePeer(head: BigInt, remoteForkId: ForkId) =
        ForkIdValidator
          .validatePeer[IO](ethGenesisHash, ethForksList)(head, remoteForkId)
          .unsafeRunSync()

      // Local is mainnet Petersburg, remote announces the same. No future fork is announced.
      validatePeer(7987396, ForkId(0x668db0afL, None)) shouldBe Connect

      // Local is mainnet Petersburg, remote announces the same. Remote also announces a next fork
      // at block 0xffffffff, but that is uncertain.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(ForkIdValidator.maxUInt64))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, and it's also aware of Petersburg (e.g. updated node before the fork). We
      // don't know if Petersburg passed yet (will pass) or not.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(7280000))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces the same. Remote also announces a next fork
      // at block 0xffffffff, but that is uncertain.
      validatePeer(7987396, ForkId(0x668db0afL, Some(ForkIdValidator.maxUInt64))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, but it's not yet aware of Petersburg (e.g. non updated node before the fork).
      // In this case we don't know if Petersburg passed yet or not.
      validatePeer(7279999, ForkId(0xa00bc324L, None)) shouldBe Connect

      validatePeer(7279999, ForkId(0xa00bc324L, Some(7280000))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, and it's also aware of some random fork (e.g. misconfigured Petersburg). As
      // neither forks passed at neither nodes, they may mismatch, but we still connect for now.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(ForkIdValidator.maxUInt64))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces Byzantium + knowledge about Petersburg. Remote
      // is simply out of sync, accept.
      validatePeer(7987396, ForkId(0xa00bc324L, Some(7280000))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces Spurious + knowledge about Byzantium. Remote
      // is definitely out of sync. It may or may not need the Petersburg update, we don't know yet.
      validatePeer(7987396, ForkId(0x3edd5b10L, Some(4370000))) shouldBe Connect

      // Local is mainnet Byzantium, remote announces Petersburg. Local is out of sync, accept.
      validatePeer(7279999, ForkId(0x668db0afL, None)) shouldBe Connect

      // Local is mainnet Spurious, remote announces Byzantium, but is not aware of Petersburg. Local
      // out of sync. Local also knows about a future fork, but that is uncertain yet.
      validatePeer(4369999, ForkId(0xa00bc324L, None)) shouldBe Connect

      // Local is mainnet Petersburg. remote announces Byzantium but is not aware of further forks.
      // Remote needs software update.
      validatePeer(7987396, ForkId(0xa00bc324L, None)) shouldBe ErrRemoteStale

      // Local is mainnet Petersburg, and isn't aware of more forks. Remote announces Petersburg +
      // 0xffffffff. Local needs software update, reject.
      validatePeer(7987396, ForkId(0x5cddc0e1L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Byzantium, and is aware of Petersburg. Remote announces Petersburg +
      // 0xffffffff. Local needs software update, reject.
      validatePeer(7279999, ForkId(0x5cddc0e1L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Petersburg, remote announces incompatible fork ID.
      validatePeer(7987396, ForkId(0xafec6b27L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Petersburg, far in the future. Remote announces Gopherium (non existing fork)
      // at some future block 88888888, for itself, but past block for local. Local is incompatible.
      //
      // This case detects non-upgraded nodes with majority hash power (typical Ropsten mess).
      validatePeer(88888888, ForkId(0x668db0afL, Some(88888888))) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Byzantium. Remote is also in Byzantium, but announces Gopherium (non existing
      // fork) at block 7279999, before Petersburg. Local is incompatible.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(7279999L))) shouldBe ErrLocalIncompatibleOrStale
    }

    /** WI-13 / issue #1429: block-number forks must be compared against the head NUMBER, timestamp forks (EIP-2124 +
      * EIP-6122) against the head TIMESTAMP — never one axis for both. Before this fix, `validatePeer` had no timestamp
      * parameter at all and compared every fork (block AND timestamp) against `currentHeight` alone. Since block
      * heights and unix timestamps live in disjoint numeric ranges (mainnet is ~23,000,000; Shanghai's timestamp fork
      * is 1,681,338,455), `currentHeight < timestampFork` was true forever, so no timestamp fork ever counted as passed
      * locally, regardless of how far the chain had actually progressed in time.
      *
      * Rows adapted from go-ethereum `core/forkid/forkid_test.go` `TestValidation`'s "Block to timestamp transition"
      * and "Timestamp based" sections (master @920c0777, mainnet-only rows — legacyConfig rows are already covered
      * above using this file's own pre-existing block-only fork list). `ethConf` is the same SHIPPED `eth-chain.conf`
      * fixture `ForkIdEthMainnetSpec` proves matches go-ethereum's block/timestamp fork lists exactly, so these
      * checksums are not re-derived by hand here — they are go-ethereum's own, cross-checked by that other spec.
      */
    "correctly validate ETH mainnet peers across the block-to-timestamp transition (EIP-2124/6122)" in {
      val ethConf = config.blockchains("eth")

      def validate(head: BigInt, time: Long, remoteForkId: ForkId) =
        ForkIdValidator.validatePeer[IO](ethGenesisHash, 0L, ethConf)(head, time, remoteForkId).unsafeRunSync()

      // --- Block-to-timestamp transition ---

      // Local is Gray Glacier only (so it knows Shanghai is next). No future fork remotely announced.
      validate(15050000, 0, ForkId(0xf0afd0e3L, None)) shouldBe Connect

      // Same, but remote also knows Shanghai is next. Uncertain whether it has passed; connect.
      validate(15050000, 0, ForkId(0xf0afd0e3L, Some(1681338455L))) shouldBe Connect

      // Local is exactly on Shanghai; remote announces Gray Glacier + knowledge of Shanghai (rule #2 subset,
      // correct next fork). Remote is simply out of sync.
      validate(20000000, 1681338455L, ForkId(0xf0afd0e3L, Some(1681338455L))) shouldBe Connect

      // Local is well past Shanghai; same remote state. Still simply out of sync.
      validate(20123456L, 1681338456L, ForkId(0xf0afd0e3L, Some(1681338455L))) shouldBe Connect

      // Local is Shanghai; remote announces Arrow Glacier + knowledge of Gray Glacier (rule #3 superset —
      // remote is ahead, local is the one syncing).
      validate(20000000, 1681338455L, ForkId(0x20c327fcL, Some(15050000))) shouldBe Connect

      // Local is Gray Glacier (block-domain only, hasn't reached its own first timestamp fork); remote is
      // already at Shanghai (superset — remote is ahead).
      validate(15050000, 0, ForkId(0xdce96c2dL, None)) shouldBe Connect

      // Local is Arrow Glacier; remote is ahead at Gray Glacier (superset).
      validate(13773000, 0, ForkId(0xf0afd0e3L, None)) shouldBe Connect

      // Local is Shanghai; remote announces Gray Glacier with no knowledge of any fork beyond it (subset,
      // WRONG next — Gray Glacier's remote software has never heard Shanghai exists). Remote is stale.
      validate(20000000, 1681338455L, ForkId(0xf0afd0e3L, None)) shouldBe ErrRemoteStale

      // Local is Gray Glacier, aware Shanghai is next; remote matches Gray Glacier's hash but its next fork
      // is a bogus checksum baked in (0xffffffff folded into the CRC). Local needs a software update.
      validate(15050000, 0, ForkId(0x27a60dbdL, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is Shanghai (timestamp-domain state); same bogus-fork trick against the Shanghai checksum.
      validate(15050000, 0, ForkId(0x726f5618L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is Gray Glacier by block count, far in the future by real time. Remote's hash matches local's
      // current (block-domain) state, but announces a `next` that is itself a timestamp — and by local's
      // OWN clock, already passed. `effectiveHead` alone (a block number) would miss this; the
      // `next > timestampThreshold && currentTimestamp >= next` cross-check (mirroring go-ethereum's
      // `newFilter` exactly) is what catches it. Detects non-upgraded majority-hashpower nodes.
      validate(888888888, 1660000000L, ForkId(0xf0afd0e3L, Some(1660000000L))) shouldBe ErrLocalIncompatibleOrStale

      // --- Timestamp-based ---

      // Local is Shanghai, remote announces the same, no future fork known. Connect.
      validate(20000000, 1681338455L, ForkId(0xdce96c2dL, None)) shouldBe Connect

      // Local is Shanghai only (aware Cancun is next); remote also Shanghai and also aware of Cancun.
      // Uncertain whether it has passed; connect.
      validate(20000000, 1668000000L, ForkId(0xdce96c2dL, Some(1710338135L))) shouldBe Connect

      // Local is exactly on Cancun; remote announces Shanghai + knowledge of Cancun (subset, correct next).
      validate(21000000, 1710338135L, ForkId(0xdce96c2dL, Some(1710338135L))) shouldBe Connect

      // Local is Shanghai; remote is already at Cancun (superset — remote is ahead).
      validate(21000000, 1700000000L, ForkId(0x9f3d2254L, None)) shouldBe Connect

      // Local is Cancun; remote announces Shanghai with no knowledge of any fork beyond it. Remote is stale
      // — the exact WI-13 symptom: a non-upgraded peer must be rejected once local has genuinely crossed a
      // timestamp fork, not silently accepted because `currentHeight` never "reaches" a timestamp value.
      validate(21000000, 1710338135L, ForkId(0xdce96c2dL, None)) shouldBe ErrRemoteStale

      // Local is Shanghai, aware Cancun is next; remote's hash matches but its bogus `next` is a
      // fork-checksum with 0xffffffff folded in. Local needs a software update.
      validate(20000000, 1681338455L, ForkId(0x726f5618L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is Shanghai; remote's hash is a random impostor that never appears in local's checksum chain.
      validate(20000000, 1681338455L, ForkId(0x12345678L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is at the tail fork (BPO2), far in the future on both axes. Remote's hash matches local's own
      // tail state, but announces a `next` already passed by local's own timestamp. Reject via the
      // same-domain branch of rule 1a (both `effectiveHead` and `next` are timestamps here).
      validate(88888888, 8888888888L, ForkId(0x07c9462eL, Some(8888888888L))) shouldBe ErrLocalIncompatibleOrStale
    }

    /** WI-13 acceptance criterion: "rejecting a stale peer across the Amsterdam boundary and accepting an upgraded one
      * before it." Sepolia's Amsterdam activation is 2026-10-06 13:53:36 UTC (timestamp 1791294816). All four ForkId
      * values below are `ForkIdSepoliaSpec`'s own verified `ForkId.create` outputs for BPO2 and Amsterdam — this spec
      * only asserts what `validatePeer` does when handed those exact ids, so a divergence here is a
      * `validatePeer`-specific regression, not a re-test of `ForkId.create`.
      */
    "reject a stale peer and accept an upgraded one across Sepolia's Amsterdam boundary" in {
      val sepoliaConf = config.blockchains("sepolia")
      val sepoliaGenesisHash = ByteString(
        Hex.decode("25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9")
      )
      val sepoliaGenesisTimestamp = 1633267481L
      val amsterdamTimestamp = 1791294816L

      def validate(time: Long, remoteForkId: ForkId) =
        // Block number fixed at 1735372 throughout (one past Sepolia's only block fork, MergeNetsplit) —
        // isolating the timestamp axis, the same convention ForkIdSepoliaSpec uses.
        ForkIdValidator
          .validatePeer[IO](sepoliaGenesisHash, sepoliaGenesisTimestamp, sepoliaConf)(
            BigInt(1735372),
            time,
            remoteForkId
          )
          .unsafeRunSync()

      // Local is past Amsterdam. Remote announces the pre-Amsterdam (BPO2) checksum with no knowledge that
      // Amsterdam even exists (`next=None`) — a genuinely stale, non-upgraded peer. Before this fix, EVERY
      // timestamp fork compared as "not yet passed" against any real Sepolia block number, so local's own
      // state would have frozen at the last BLOCK fork (MergeNetsplit) and this stale peer would have been
      // accepted as a checksum superset. Now correctly rejected.
      validate(amsterdamTimestamp, ForkId(0x268956b6L, None)) shouldBe ErrRemoteStale

      // Same local state; remote correctly knows Amsterdam is its next fork (subset, correct next — remote
      // is simply syncing, not stale). Connect.
      validate(amsterdamTimestamp, ForkId(0x268956b6L, Some(amsterdamTimestamp))) shouldBe Connect

      // Local is still on BPO2 (one instant before Amsterdam); remote has already crossed it (superset —
      // remote is ahead). Accepting the upgraded peer BEFORE local's own Amsterdam activation.
      validate(amsterdamTimestamp - 1, ForkId(0x6c1d9423L, None)) shouldBe Connect

      // Both local and remote are past Amsterdam with no further fork known. Connect.
      validate(2000000000L, ForkId(0x6c1d9423L, None)) shouldBe Connect
    }
  }
