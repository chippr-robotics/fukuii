package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.MissingAncestorProbe.Target
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager.BeaconHead
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

/** Which block, if any, do we ask peers for when the consensus layer names a head?
  *
  * The shape under test is hive's "Invalid Missing Ancestor Syncing ReOrg … CanonicalReOrg=True": we hold a canonical
  * chain, the CL hands us a side-chain head whose parent we never saw (stored by hash only, as `engine_newPayload` does
  * for an ACCEPTED payload), and the only peer handshook at genesis. The positive cases name exactly the missing block
  * and its height. The negative cases are what keep a node that is NOT syncing silent: a head that was executed,
  * canonical or not, has nothing missing behind it.
  */
class MissingAncestorProbeSpec extends AnyFlatSpec with Matchers:

  /** Canonical genesis..B3, executed and saved as best, plus a side chain S3, S4, S5 forking at B2 that is NOT stored.
    * Each test stores only the side blocks it needs, and how.
    */
  trait Setup extends EphemBlockchainTestSetup:
    val canonical: List[Block] = BlockHelpers.genesis +: BlockHelpers.generateChain(3, BlockHelpers.genesis)
    canonical.foldLeft(ChainWeight.zero) { (w, b) =>
      val weight = w.increase(b.header)
      blockchainWriter.save(b, Nil, weight, saveAsBestBlock = true)
      weight
    }
    val side: List[Block] = BlockHelpers.generateChain(3, canonical(2))
    val List(s3, s4, s5) = side: @unchecked

    /** How `engine_newPayload` stores an ACCEPTED payload: header and body by hash, no number mapping, no receipts. */
    def storeByHashOnly(b: Block): Unit = blockchainWriter.storeBlockByHashOnly(b).commit()

    def headOf(b: Block): BeaconHead = BeaconHead(b.hash.value, Some(b.header))

  "MissingAncestorProbe.target" should "name the unknown parent of a CL head held by hash only — the hive P9 shape" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    storeByHashOnly(s5) // S4 never arrived: it is the block the peer has and we need

    MissingAncestorProbe.target(headOf(s5), blockchainReader) shouldBe Some(Target(s4.hash.value, s4.number.value))

  it should "walk back through unexecuted by-hash blocks to the first one we do not hold" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    storeByHashOnly(s4)
    storeByHashOnly(s5)

    MissingAncestorProbe.target(headOf(s5), blockchainReader) shouldBe Some(Target(s3.hash.value, s3.number.value))

  it should "ask for nothing when the CL head is canonical — a node keeping up sends no probe" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    MissingAncestorProbe.target(headOf(canonical.last), blockchainReader) shouldBe None

  it should "ask for nothing when the CL head was executed off the canonical chain" taggedAs (UnitTest, SyncTest) in
    new Setup:
      // A side block the engine executed: receipts stored, no number mapping. Its ancestry is ours.
      storeByHashOnly(s5)
      blockchainWriter.storeReceipts(s5.hash, Nil).commit()

      MissingAncestorProbe.target(headOf(s5), blockchainReader) shouldBe None

  it should "ask for nothing when the walk reaches an executed ancestor without a gap" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    // S3 executed, S4 and S5 held but not executed: nothing is MISSING, so there is nothing a peer could send us.
    storeByHashOnly(s3)
    blockchainWriter.storeReceipts(s3.hash, Nil).commit()
    storeByHashOnly(s4)
    storeByHashOnly(s5)

    MissingAncestorProbe.target(headOf(s5), blockchainReader) shouldBe None

  it should "ask for nothing when the CL head itself is unknown — its height is unknown too" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    MissingAncestorProbe.target(BeaconHead(s5.hash.value, None), blockchainReader) shouldBe None

  it should "ask for nothing for the genesis head" taggedAs (UnitTest, SyncTest) in new Setup:
    MissingAncestorProbe.target(headOf(canonical.head), blockchainReader) shouldBe None

  it should "give up past the walk bound rather than walk an unbounded stored-header chain" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup:
    storeByHashOnly(s4)
    storeByHashOnly(s5)

    MissingAncestorProbe.target(headOf(s5), blockchainReader, maxWalk = 0) shouldBe None
    MissingAncestorProbe.target(headOf(s5), blockchainReader, maxWalk = 1) shouldBe
      Some(Target(s3.hash.value, s3.number.value))
