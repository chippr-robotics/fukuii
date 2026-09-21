package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import boopickle.DefaultBasic.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Picklers.given

/** Pins the boopickle ordinal of every `HeaderExtraFields` variant to its wire byte.
  *
  * **Why this exists.** `CompositePickler.addConcreteType` assigns each concrete type an index from its REGISTRATION
  * ORDER, and that index is what goes on the wire: `pickle` writes `idx - 1` and `unpickle` reads it back as a position
  * into the same list. Appending a variant is therefore backward compatible; inserting one in the middle silently
  * renumbers every variant after it.
  *
  * Nothing else in the suite catches that. `PicklerOlympiaSpec` round-trips through the *current* registry, so it
  * passes whatever ordinals happen to be in effect — pickle and unpickle shift together. A mid-list insertion would
  * leave the entire test suite green while making every already-persisted record decode as the wrong variant.
  *
  * **The blast radius is not just fast sync.** `Picklers.given` is imported by `BlockHeadersStorage` and
  * `BlockBodiesStorage`, i.e. the persistent header database for the whole chain — ETC included, where the only
  * reachable variants are `HefEmpty` (pre-Olympia) and `HefPostOlympia` (ECIP-1111 base fee). An ordinal shift
  * corrupts an existing ETC datadir on upgrade, and the node would decode a `HefEmpty` record as `HefPostOlympia` or
  * worse rather than failing loudly.
  *
  * So: **append new variants, never insert.** This spec is the ratchet that makes that a build failure instead of a
  * code-review convention.
  */
class PicklerOrdinalRatchetSpec extends AnyFlatSpec with Matchers:

  /** Registration order in `Picklers.extraFieldsPickler`, oldest first. Append here; never insert. */
  private val registrationOrder: Seq[(String, HeaderExtraFields)] = Seq(
    "HefEmpty" -> HefEmpty,
    "HefPostOlympia" -> HefPostOlympia(baseFee = BigInt(7)),
    "HefPostShanghai" -> HefPostShanghai(baseFee = BigInt(7), withdrawalsRoot = ByteString.empty),
    "HefPostCancun" -> HefPostCancun(
      baseFee = BigInt(7),
      withdrawalsRoot = ByteString.empty,
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = ByteString.empty
    ),
    "HefPostPrague" -> HefPostPrague(
      baseFee = BigInt(7),
      withdrawalsRoot = ByteString.empty,
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = ByteString.empty,
      requestsHash = ByteString.empty
    ),
    "HefPostAmsterdam" -> HefPostAmsterdam(
      baseFee = BigInt(7),
      withdrawalsRoot = ByteString.empty,
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = ByteString.empty,
      requestsHash = ByteString.empty,
      blockAccessListHash = ByteString.empty,
      slotNumber = BigInt(0)
    )
  )

  private def leadingOrdinalByte(v: HeaderExtraFields): Int =
    val buf = Pickle.intoBytes(v)
    val head = buf.get()
    head.toInt & 0xff

  "HeaderExtraFields boopickle ordinals" should "be stable, dense and in registration order" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val actual = registrationOrder.map { case (name, v) => name -> leadingOrdinalByte(v) }

    // Compared as a whole sequence on purpose: a failure prints every variant's actual ordinal,
    // which shows at a glance whether one was inserted mid-list (a contiguous shift) or the
    // encoding itself changed (everything moves).
    actual shouldBe Seq(
      "HefEmpty" -> 1,
      "HefPostOlympia" -> 2,
      "HefPostShanghai" -> 3,
      "HefPostCancun" -> 4,
      "HefPostPrague" -> 5,
      "HefPostAmsterdam" -> 6
    )
  }

  it should "keep the two ETC-reachable variants on their original ordinals" taggedAs (UnitTest, ConsensusTest) in {
    // Stated separately from the table above because this is the ETC consensus-relevant subset:
    // ETC produces only these two shapes, so these two ordinals are what an existing ETC datadir
    // has on disk. They must never move, whatever happens to the ETH-side variants after them.
    leadingOrdinalByte(HefEmpty) shouldBe 1
    leadingOrdinalByte(HefPostOlympia(baseFee = BigInt(7))) shouldBe 2
  }

  it should "round-trip every variant through the registry it pins" taggedAs (UnitTest, ConsensusTest) in {
    // Guards the pairing: an ordinal is only meaningful if it decodes back to the same variant.
    registrationOrder.foreach { case (name, v) =>
      withClue(s"$name: ") {
        val buf = Pickle.intoBytes(v)
        Unpickle[HeaderExtraFields].fromBytes(buf) shouldBe v
      }
    }
  }
