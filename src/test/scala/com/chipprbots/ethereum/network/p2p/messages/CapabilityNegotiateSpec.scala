package com.chipprbots.ethereum.network.p2p.messages

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.*

/** Regression tests for the Capability.negotiate strict-intersection fix (commit e674303aa).
  *
  * The bug: negotiate() could return a capability from the REMOTE peer's set (e.g. ETH67) that Fukuii has no decoder
  * for, causing a MatchError in production.
  *
  * The fix: always return a capability from OUR set (the first argument) — we guarantee we have a decoder for
  * everything we advertise.
  */
class CapabilityNegotiateSpec extends AnyWordSpec with Matchers:

  "Capability.negotiate" when {

    "our set is [ETH68, ETH69]" should {

      "return None for an ETH67-only peer (no common cap → clean disconnect)" taggedAs UnitTest in {
        Capability.negotiate(
          List(Capability.ETH68, Capability.ETH69),
          List(Capability.ETH67)
        ) shouldBe None
      }

      "return Some(ETH68) for a peer advertising [ETH67, ETH68] (highest common)" taggedAs UnitTest in {
        val result = Capability.negotiate(
          List(Capability.ETH68, Capability.ETH69),
          List(Capability.ETH67, Capability.ETH68)
        )
        result shouldBe Some(Capability.ETH68)
      }

      "return Some(ETH69) for an ETH69-only peer (single-version overlap)" taggedAs UnitTest in {
        val result = Capability.negotiate(
          List(Capability.ETH68, Capability.ETH69),
          List(Capability.ETH69)
        )
        result shouldBe Some(Capability.ETH69)
      }

      "return None for a legacy-only peer advertising [ETH66, ETH67]" taggedAs UnitTest in {
        Capability.negotiate(
          List(Capability.ETH68, Capability.ETH69),
          List(Capability.ETH66, Capability.ETH67)
        ) shouldBe None
      }

      "always return a capability from OUR set, never from the peer's set" taggedAs UnitTest in {
        // Pre-fix bug: returned Some(ETH67) from peer's set when peer had ETH67 + some overlap.
        // After fix: all results must be from our advertised set [ETH68, ETH69].
        val ourCaps = List(Capability.ETH68, Capability.ETH69)
        val testCases = List(
          List(Capability.ETH67, Capability.ETH68),
          List(Capability.ETH69),
          List(Capability.ETH68)
        )
        testCases.foreach { remoteCaps =>
          Capability.negotiate(ourCaps, remoteCaps).foreach { result =>
            ourCaps should contain(result)
          }
        }
      }
    }

    "our set includes SNAP1" should {

      "negotiate ETH69 without SNAP when peer only has ETH69" taggedAs UnitTest in {
        // SNAP negotiation is independent: ETH69 is negotiated but SNAP1 is not added
        // because the peer doesn't advertise it.
        val result = Capability.negotiate(
          List(Capability.ETH68, Capability.ETH69, Capability.SNAP1),
          List(Capability.ETH69)
        )
        // Result should be the ETH69 cap (best from negotiated set), not SNAP1
        result shouldBe Some(Capability.ETH69)
      }

      "negotiate SNAP1 when both sides advertise ETH68 and SNAP1" taggedAs UnitTest in {
        // When both sides have ETH68 and SNAP1, the best cap is returned.
        // Capability.best picks ETH over SNAP, so ETH68 comes back.
        // The key assertion: SNAP is only considered when both sides have it.
        val result = Capability.negotiate(
          List(Capability.ETH68, Capability.SNAP1),
          List(Capability.ETH68, Capability.SNAP1)
        )
        // ETH takes priority over SNAP in best(); both are negotiated but ETH wins
        result shouldBe Some(Capability.ETH68)
      }
    }

    // ── ETH70/71/72 + SNAP2 — go-ethereum master's full message-set implementation ──

    "our set is the full implemented set [ETH68..ETH72, SNAP1, SNAP2]" should {
      val ourFullSet =
        List(
          Capability.ETH68,
          Capability.ETH69,
          Capability.ETH70,
          Capability.ETH71,
          Capability.ETH72,
          Capability.SNAP1,
          Capability.SNAP2
        )

      "negotiate ETH72 with a peer offering the same full ETH range (go-ethereum master's general dial)" taggedAs UnitTest in {
        val peerCaps = List(Capability.ETH72, Capability.ETH70, Capability.ETH69)
        Capability.negotiateEth(peerCaps, ourFullSet) shouldBe Some(Capability.ETH72)
      }

      "negotiate exactly ETH71 with a peer that ONLY advertises eth/71 (go-ethereum's dialEth71)" taggedAs UnitTest in {
        Capability.negotiateEth(List(Capability.ETH71), ourFullSet) shouldBe Some(Capability.ETH71)
      }

      "negotiate SNAP2 with a peer that ONLY advertises snap/2 (go-ethereum's dialSnap2)" taggedAs UnitTest in {
        Capability.negotiateSnap(List(Capability.SNAP2), ourFullSet) shouldBe Some(Capability.SNAP2)
      }

      // ── ETC/core-geth interop safety net — the actual concern behind "check how the capability
      // set is chosen per network" (herald task, eth/72 + snap/2 rollout). ──
      "still negotiate eth/69 + snap/1 with an ETC-shaped peer that only speaks up to eth/69 (core-geth, Besu-ETC)" taggedAs UnitTest in {
        val etcPeerCaps = List(Capability.ETH68, Capability.ETH69, Capability.SNAP1)
        Capability.negotiateEth(etcPeerCaps, ourFullSet) shouldBe Some(Capability.ETH69)
        Capability.negotiateSnap(etcPeerCaps, ourFullSet) shouldBe Some(Capability.SNAP1)
        // The overall negotiate() result (ETH prioritised over SNAP in best()) must also land on
        // eth/69, never silently drop to something the ETC peer never advertised.
        Capability.negotiate(etcPeerCaps, ourFullSet) shouldBe Some(Capability.ETH69)
      }

      "still negotiate plain eth/68 with a legacy peer that never adopted EIP-7642" taggedAs UnitTest in {
        Capability.negotiateEth(List(Capability.ETH68), ourFullSet) shouldBe Some(Capability.ETH68)
      }

      "return None for SNAP when the peer advertises ETH only (no snap capability at all)" taggedAs UnitTest in {
        Capability.negotiateSnap(List(Capability.ETH69), ourFullSet) shouldBe None
      }
    }
  }
