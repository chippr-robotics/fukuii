package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedAt
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The p2p import path must be able to tell the Engine API that a chain is consensus-invalid.
  *
  * WHAT THIS PINS, AND WHY. Measured on fc713a9 across all 48 timeout-family hive engine tests: hive polled
  * `engine_newPayload` 41-58 times per test and fukuii answered ACCEPTED 2014 / SYNCING 2014 / VALID 912 / **INVALID
  * 0** — 4,940 status replies, not one of them INVALID. The node reaches the right verdict on the invalid chain in
  * `BlockImporter` and then has no channel to express it, so hive times out waiting for a detection that never arrives.
  * `EngineApiService.invalidBlocks` — the map that makes newPayload/forkchoiceUpdated answer INVALID — had no writer
  * outside its own class.
  *
  * The symmetric risk is worse than the bug. An earlier attempt reported every `BlockExecutionError` except
  * `MPTError(MissingNode)` and thereby marked honest blocks permanently invalid, because a merely-missing contract code
  * surfaces as `ValidationAfterExecError("Block has invalid gas used")` — `InMemoryWorldStateProxy.getCode` returns
  * `ByteString.empty` rather than throwing. So roughly half the assertions below are NEGATIVE: they pin the errors that
  * must NOT be reported. Treat a failure of one of those as a chain-halting regression, not a test nit.
  */
// scalastyle:off magic.number
class InvalidChainReportingSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with NormalPatience
    with MockFactory:

  import InvalidChainReportingSpec.*

  // ---------------------------------------------------------------------------------------------------------------
  // 1. Classification. Which execution errors PROVE consensus-invalidity, and which only prove we cannot judge yet.
  // ---------------------------------------------------------------------------------------------------------------

  "provesConsensusInvalid" should "report typed pre-execution header and body failures" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // The 'NEW' column of the classification doc: unreachable on the bulk import path before b4cdc30, reachable now.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError)
    ) shouldBe true
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.HeaderTimestampError)
    ) shouldBe true
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.HeaderNumberError)
    ) shouldBe true
    // PoS-specific: a post-merge block carrying ommers or a non-zero nonce.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.PoSOmmersError)
    ) shouldBe true
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.PoSNonceError(ByteString(1, 2, 3)))
    ) shouldBe true
    // Structural body failures decided from the block's own bytes.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(StdBlockValidator.BlockTransactionsHashError)
    ) shouldBe true
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(StdBlockValidator.BlockWithdrawalsRootError)
    ) shouldBe true
  }

  it should "report transaction-level execution failures" taggedAs (UnitTest, ConsensusTest) in {
    // The five hive Transaction* invalid-ancestor variants (signature, nonce, gas, gas price, value) all land here.
    // Safe because the account lookup behind those checks (InMemoryWorldStateProxy.getAccount) THROWS on a missing
    // trie node rather than returning an empty account, so incomplete state cannot masquerade as a bad nonce.
    InvalidChainReporter.provesConsensusInvalid(
      TxsExecutionError(null, null, "Account does not exist or has insufficient balance")
    ) shouldBe true
  }

  it should "report state-root and receipts-root mismatches" taggedAs (UnitTest, ConsensusTest) in {
    // Unambiguous ONLY because StdValidators.validateBlockAfterExecution checks gasUsed FIRST and returns early:
    // reaching the state-root branch proves execution completed. See the comment guarding that ordering.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationAfterExecError("Block has invalid state root hash, expected aa but got bb")
    ) shouldBe true
    InvalidChainReporter.provesConsensusInvalid(
      ValidationAfterExecError("BlockReceiptsHashError")
    ) shouldBe true
  }

  it should "NOT report missing state — this is what makes the channel safe" taggedAs (UnitTest, ConsensusTest) in {
    InvalidChainReporter.provesConsensusInvalid(
      MPTError(new MissingNodeException(ByteString("node")))
    ) shouldBe false
    InvalidChainReporter.provesConsensusInvalid(MissingParentError) shouldBe false
  }

  it should "NOT report a gas-used mismatch — indistinguishable from missing contract code" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // THE case that sank the previous attempt. BlockImporter disambiguates this one with
    // findMissingContractCode and reports only on the `None` arm; it is never reported from here.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationAfterExecError("Block has invalid gas used, expected 183600 but got 0")
    ) shouldBe false
  }

  it should "NOT report a missing parent header — that is transient, not invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Same class of bug as the gas-used case, one layer up: the parent simply is not in storage yet.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.HeaderParentNotFoundError)
    ) shouldBe false
    // String-carrying catch-all, no type-level guarantee.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(BlockHeaderError.HeaderUnexpectedError("something went wrong"))
    ) shouldBe false
  }

  it should "NOT report ommer failures — every ommer rule is relative to local ancestry" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Costs nothing on ETH: a post-merge block with ommers fails as PoSOmmersError (a BlockHeaderError), reported above.
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(OmmersError.OmmerParentIsNotAncestorError)
    ) shouldBe false
    InvalidChainReporter.provesConsensusInvalid(
      ValidationBeforeExecError(OmmersError.OmmersHeaderError(List(BlockHeaderError.HeaderParentNotFoundError)))
    ) shouldBe false
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 2. The import path actually calls the channel, with the right latestValidHash.
  // ---------------------------------------------------------------------------------------------------------------

  "ConsensusImpl" should "report a branch-extension failure with LVH = the failing block's parent" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    failAt(branch(1), ValidationBeforeExecError(BlockHeaderError.HeaderTimestampError))

    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

    // The failing block, named with the last block we actually executed as latestValidHash, AND the rest of the
    // branch behind it: those blocks were never executed, they are parentHash-linked descendants of a block proven
    // invalid, and the Engine API has no other way to learn they are poisoned (they never arrive via newPayload).
    // Every descendant carries the SAME latestValidHash — the most recent valid ancestor is the same block for all
    // of them. Order matters: the failing block first, then descending.
    reported.toList shouldBe List(
      branch(1).hash.value -> branch(0).hash.value,
      branch(2).hash.value -> branch(0).hash.value
    )

  it should "report with LVH = the branch parent when nothing in the batch executed" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    failAt(branch(0), ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError))

    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

    reported.toList shouldBe List(
      branch(0).hash.value -> initialBestBlock.hash.value,
      branch(1).hash.value -> initialBestBlock.hash.value,
      branch(2).hash.value -> initialBestBlock.hash.value
    )

  it should "report a SIDE-CHAIN failure with a validated non-canonical LVH, not the canonical head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // This is the shape hive's invalid-ancestor family builds: an alt chain, heavier than canonical, whose block at
    // InvalidIndex is bad. The doc records hive asserting latestValidHash == altChainPayloads[InvalidIndex-1] —
    // a block we validated but which was NOT canonical when we judged it. Getting this wrong fails the test just as
    // surely as not reporting at all, so it is asserted separately from the extend-canonical case above.
    val altChain: List[Block] =
      BlockHelpers.generateChain(
        3,
        initialChain(2),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )
    failAt(altChain(1), ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError))

    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(altChain)).unsafeToFuture())(_ => ())

    reported.toList shouldBe List(
      altChain(1).hash.value -> altChain(0).hash.value,
      altChain(2).hash.value -> altChain(0).hash.value
    )
    // And the LVH is genuinely not the pre-reorg canonical tip.
    altChain(0).hash.value should not be initialBestBlock.hash.value

  it should "NOT report when the branch fails on missing state" taggedAs (UnitTest, ConsensusTest) in
    new ConsensusSetup:
      val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
      failAt(branch(1), MissingParentError)

      whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

      reported shouldBe empty

  it should "NOT report a gas-used mismatch — BlockImporter owns that case" taggedAs (UnitTest, ConsensusTest) in
    new ConsensusSetup:
      val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
      failAt(branch(1), ValidationAfterExecError("Block has invalid gas used, expected 183600 but got 0"))

      whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

      reported shouldBe empty

  it should "report a gas-used mismatch MID-BATCH when the block's own receipts prove it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // The hive GasUsed P8 shape: blocks before the bad one execute, so this is a PARTIAL batch, which
    // ConsensusAdapter maps to BlockImportedToTop and BlockImporter never sees. Before this change a gas-used
    // mismatch here was reported by nobody. With the receipts-proof marker, ConsensusImpl reports it itself.
    val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    failAt(
      branch(1),
      ValidationAfterExecError(
        "Block has invalid gas used, expected 183600 but got 0; " +
          com.chipprbots.ethereum.consensus.validators.std.StdValidators.HeaderGasContradictsReceiptsMarker
      )
    )

    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

    reported.toList shouldBe List(
      branch(1).hash.value -> branch(0).hash.value,
      branch(2).hash.value -> branch(0).hash.value
    )

  it should "be inert with no reporter — the ETC/Mordor/Gorgoroth configuration" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // NodeBuilder passes Some(LateBound) on every network, but nothing binds it unless network.engine-api.enabled.
    // This pins the other half: a ConsensusImpl built with no reporter at all behaves exactly as before.
    override lazy val reporterOpt: Option[InvalidChainReporter] = None
    val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    failAt(branch(1), ValidationBeforeExecError(BlockHeaderError.HeaderTimestampError))

    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

    reported shouldBe empty

  // ---------------------------------------------------------------------------------------------------------------
  // 2b. Depth. The verdict must reach a CL-supplied tip that is MORE THAN ONE HOP above the invalid block.
  //
  //     Measured on hive `engine` 6c8bc97, "Invalid Missing Ancestor Syncing ReOrg": the cross-tab splits perfectly
  //     on the distance from the invalid block to the payload the CL hands us. Invalid block == the tip's direct
  //     parent (hive's "Invalid P9"): 14 of 16 pass. Invalid block two hops down ("Invalid P8"): 0 of 16 pass, for
  //     every corrupted field (GasLimit, GasUsed, ReceiptsRoot, Timestamp, StateRoot). The intermediate block is
  //     fetched from a peer, so it is in no engine-side index, and the poison chain stops there.
  // ---------------------------------------------------------------------------------------------------------------

  "provenDescendants" should "return the whole parentHash-linked suffix" taggedAs (UnitTest, ConsensusTest) in {
    val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
    InvalidChainReporter.provenDescendants(chain.head, chain.tail) shouldBe chain.tail
  }

  it should "stop at the first broken link rather than skipping over it" taggedAs (UnitTest, ConsensusTest) in {
    // The safety property. A batch that is NOT one contiguous chain must not have a verdict carried across the gap:
    // a block whose parent is not the block we just proved invalid is not proven invalid by anything here.
    val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
    val unrelated = BlockHelpers.generateChain(1, BlockHelpers.genesis).head
    val withGap = List(chain(1), unrelated, chain(3))
    InvalidChainReporter.provenDescendants(chain.head, withGap) shouldBe List(chain(1))
  }

  it should "report nothing when the first candidate is not a child" taggedAs (UnitTest, ConsensusTest) in {
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    val unrelated = BlockHelpers.generateChain(1, BlockHelpers.genesis).head
    InvalidChainReporter.provenDescendants(chain.head, List(unrelated, chain(1))) shouldBe Nil
  }

  it should "report nothing for an empty suffix" taggedAs (UnitTest, ConsensusTest) in {
    InvalidChainReporter.provenDescendants(BlockHelpers.genesis, Nil) shouldBe Nil
  }

  "InvalidChainReporter.LateBound" should "be a no-op until bound" taggedAs (UnitTest, ConsensusTest) in {
    val holder = new InvalidChainReporter.LateBound
    holder.isBound shouldBe false
    noException should be thrownBy holder.reportInvalid(ByteString("a"), ByteString("b"))

    val seen = mutable.ListBuffer.empty[(ByteString, ByteString)]
    holder.bind { (h, lvh) =>
      seen += (h -> lvh); ()
    }
    holder.isBound shouldBe true
    holder.reportInvalid(ByteString("a"), ByteString("b"))
    seen.toList shouldBe List(ByteString("a") -> ByteString("b"))
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 3. End to end: a report from the import path changes what the Engine API answers the consensus layer.
  //    This is the assertion the 48 hive failures reduce to.
  // ---------------------------------------------------------------------------------------------------------------

  "EngineApiService" should "answer forkchoiceUpdated INVALID for a head the import path reported" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EngineSetup:
    val invalidHash: ByteString = BlockHelpers.randomHash()
    val lastValidHash: ByteString = BlockHelpers.randomHash()
    val fcs: ForkChoiceState = ForkChoiceState(invalidHash, lastValidHash, lastValidHash)

    // BEFORE: this is the measured failure mode — never INVALID, so hive polls until it times out.
    whenReady(engineApi.forkchoiceUpdated(fcs, None).unsafeToFuture()) { before =>
      before.map(_.payloadStatus.status) should not be Right(Invalid)
    }

    // The import path speaks.
    engineApi.invalidChainReporter.reportInvalid(invalidHash, lastValidHash)

    // AFTER: INVALID, carrying the last validated ancestor.
    whenReady(engineApi.forkchoiceUpdated(fcs, None).unsafeToFuture()) {
      case Right(response) =>
        response.payloadStatus.status shouldBe Invalid
        response.payloadStatus.latestValidHash shouldBe Some(lastValidHash)
      case Left(err) => fail(s"expected a payload status, got JSON-RPC error: $err")
    }

  it should "propagate INVALID to a newPayload child of a block the import path reported" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EngineSetup:
    // This is the exact hive shape: the invalid ANCESTOR arrives over p2p (never through newPayload), and hive then
    // asks about a DESCENDANT over the Engine API. The child must inherit both the verdict and the ancestor's
    // latestValidHash — a child of an invalid block is invalid however it reaches us.
    val invalidAncestor: ByteString = BlockHelpers.randomHash()
    val lastValidHash: ByteString = BlockHelpers.randomHash()
    val childPayload: ExecutionPayload = payloadOfUnexecutableChild(invalidAncestor)

    // BEFORE: the ancestor is unknown to us, so the child is optimistically ACCEPTED and hive learns nothing.
    whenReady(engineApi.newPayload(childPayload).unsafeToFuture()) { before =>
      before.status should not be Invalid
    }

    // The import path judged the ancestor and now says so.
    engineApi.invalidChainReporter.reportInvalid(invalidAncestor, lastValidHash)

    whenReady(engineApi.newPayload(childPayload).unsafeToFuture()) { after =>
      after.status shouldBe Invalid
      after.latestValidHash shouldBe Some(lastValidHash)
    }

  it should "answer INVALID for a CL tip whose invalid ancestor is a GRANDparent, not a parent" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // The full hive "Invalid P8" shape, both halves wired together:
    //
    //   B0 (executes)  ->  I (invalid)  ->  M (never executed, arrives only over p2p)  ->  T (the CL's tip)
    //
    // The CL gives us T and nothing else, so T is optimistically ACCEPTED and indexed under M. The backfill then
    // fetches B0, I, M from a peer; execution stops at I. Before this change the Engine API learned about I alone,
    // M was in no index, and T stayed ACCEPTED for the whole of hive's timeout — 16 of 16 P8 variants failed that
    // way. The assertion below is that T now answers INVALID, with the latestValidHash hive checks:
    // `altChainPayloads[InvalidIndex-1]`, i.e. I's parent B0 (invalid_ancestor.go:444).
    override lazy val reporterOpt: Option[InvalidChainReporter] = Some(engineApi.invalidChainReporter)

    val branch: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    val b0: Block = branch(0)
    val invalidBlock: Block = branch(1)
    val intermediate: Block = branch(2)
    val tip: ExecutionPayload = payloadOfUnexecutableChild(intermediate.hash.value)

    failAt(invalidBlock, ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError))

    // 1. The CL hands us the tip first. Its parent is unknown, so: ACCEPTED, and indexed under the intermediate.
    whenReady(engineApi.newPayload(tip).unsafeToFuture())(_.status shouldBe Accepted)

    // 2. The backfill runs and execution stops at the invalid block.
    whenReady(consensusUnderTest.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeToFuture())(_ => ())

    // 3. The tip — two hops above the invalid block — is now INVALID, with I's parent as latestValidHash.
    whenReady(engineApi.newPayload(tip).unsafeToFuture()) { after =>
      after.status shouldBe Invalid
      after.latestValidHash shouldBe Some(b0.hash.value)
    }

    // 4. And forkchoiceUpdated naming that same tip as head agrees — hive asserts both (invalid_ancestor.go:446-449).
    val fcs: ForkChoiceState = ForkChoiceState(tip.blockHash, b0.hash.value, b0.hash.value)
    whenReady(engineApi.forkchoiceUpdated(fcs, None).unsafeToFuture()) {
      case Right(response) =>
        response.payloadStatus.status shouldBe Invalid
        response.payloadStatus.latestValidHash shouldBe Some(b0.hash.value)
      case Left(err) => fail(s"expected a payload status, got JSON-RPC error: $err")
    }

  // ---------------------------------------------------------------------------------------------------------------
  // 4. A FAILED REORG reported twice. ConsensusImpl reports the true failing block; BlockImporter's gas-used arm then
  //    used to report the batch HEAD and everything hash-linked behind it, with the head's parent as latestValidHash —
  //    poisoning honest blocks and, through `put`, overwriting the correct verdict. Source-traced on 13c1e5686 for
  //    hive `Invalid Missing Ancestor Syncing ReOrg, GasUsed, CanonicalReOrg=True`.
  // ---------------------------------------------------------------------------------------------------------------

  "A failed reorg onto a gas-used-invalid side chain" should
    "leave exactly P8', P9', P10' INVALID with latestValidHash = P7' (batch = side 6..14)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new HiveGasUsedReorgSetup:
      importBatchThroughTheImporterRule(side)

      engineApi.invalidBlocksSnapshot shouldBe Map(
        p8.hash.value -> p7.hash.value,
        p9.hash.value -> p7.hash.value,
        p10.blockHash -> p7.hash.value
      )

  it should "leave exactly P8', P9', P10' INVALID when the batch also carries the common prefix 1..5" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new HiveGasUsedReorgSetup:
    // The client log's shape: `[RegularSync] headers=14 … range=[1-14]`. The batch head is then canonical block 1,
    // and the pre-fix rule reported canonical 1..5 as invalid too, with genesis as latestValidHash.
    importBatchThroughTheImporterRule(canonical.take(5) ++ side)

    engineApi.invalidBlocksSnapshot shouldBe Map(
      p8.hash.value -> p7.hash.value,
      p9.hash.value -> p7.hash.value,
      p10.blockHash -> p7.hash.value
    )

  it should "answer newPayload and forkchoiceUpdated for P10' with latestValidHash = P7' — what hive asserts" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new HiveGasUsedReorgSetup:
    importBatchThroughTheImporterRule(side)
    whenReady(engineApi.newPayload(p10).unsafeToFuture()) { r =>
      r.status shouldBe Invalid
      r.latestValidHash shouldBe Some(p7.hash.value)
    }
    whenReady(engineApi.forkchoiceUpdated(ForkChoiceState(p10.blockHash, zero32, zero32), None).unsafeToFuture()) {
      case Right(response) =>
        response.payloadStatus.status shouldBe Invalid
        response.payloadStatus.latestValidHash shouldBe Some(p7.hash.value)
      case Left(err) => fail(s"expected a payload status, got JSON-RPC error: $err")
    }

  "provenGasUsedFailure" should "keep the pre-existing answer when no failing block is named" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    BlockImporter.provenGasUsedFailure(BlockImportFailed("gas"), chain, reportingLive = true, _ => None) shouldBe
      Some(chain.head -> chain.tail)
    BlockImporter.provenGasUsedFailure(BlockImportFailed("gas"), chain, reportingLive = false, _ => None) shouldBe
      Some(chain.head -> chain.tail)
  }

  it should "keep the pre-existing answer when the named failing block IS the batch head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    val err = new BlockImportFailedAt("gas", chain.head.hash.value)
    BlockImporter.provenGasUsedFailure(err, chain, reportingLive = false, _ => fail("must not be consulted")) shouldBe
      Some(chain.head -> chain.tail)
  }

  it should "report the NAMED block, not the head, when reporting is live" taggedAs (UnitTest, ConsensusTest) in {
    val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
    val err = new BlockImportFailedAt("gas", chain(2).hash.value)
    BlockImporter.provenGasUsedFailure(err, chain, reportingLive = true, _ => None) shouldBe
      Some(chain(2) -> List(chain(3)))
  }

  it should "do nothing — and never look for missing code — on a chain with no live reporter (ETC)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
    val err = new BlockImportFailedAt("gas", chain(2).hash.value)
    BlockImporter.provenGasUsedFailure(err, chain, reportingLive = false, _ => fail("must not be consulted")) shouldBe
      None
  }

  it should "REFUSE when the named block's own contract code is missing" taggedAs (UnitTest, ConsensusTest) in {
    // The head was cleared by the caller; the named block was not. Same ambiguity the gas-used arm exists to resolve.
    val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
    val err = new BlockImportFailedAt("gas", chain(2).hash.value)
    BlockImporter.provenGasUsedFailure(
      err,
      chain,
      reportingLive = true,
      b => if b.hash == chain(2).hash then Some(BlockHelpers.randomHash()) else None
    ) shouldBe None
  }

  it should "REFUSE rather than guess when the named block is not in the batch" taggedAs (UnitTest, ConsensusTest) in {
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    val err = new BlockImportFailedAt("gas", BlockHelpers.randomHash())
    BlockImporter.provenGasUsedFailure(err, chain, reportingLive = true, _ => None) shouldBe None
  }

  "BlockImportFailedAt" should "be indistinguishable from BlockImportFailed to every existing consumer" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // The ETC-inertness argument for carrying the hash: equality, extractor and toString are inherited unchanged.
    val at: BlockImportResult = new BlockImportFailedAt("boom", BlockHelpers.randomHash())
    at shouldBe BlockImportFailed("boom")
    at.toString shouldBe BlockImportFailed("boom").toString
    at.hashCode shouldBe BlockImportFailed("boom").hashCode
    val extracted = at match
      case BlockImportFailed(error) => Some(error)
      case _                        => None
    extracted shouldBe Some("boom")
  }

  "ConsensusAdapter.reportsInvalidChains" should "be false with no reporter or an unbound one, true once bound" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new HiveGasUsedReorgSetup:
    adapterWith(None).reportsInvalidChains shouldBe false
    val holder = new InvalidChainReporter.LateBound
    adapterWith(Some(holder)).reportsInvalidChains shouldBe false
    holder.bind(engineApi.invalidChainReporter)
    adapterWith(Some(holder)).reportsInvalidChains shouldBe true

  "The invalid-block registry" should "keep the first verdict and hand it to descendants on a conflicting re-report" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EngineSetup:
    val invalid: ByteString = BlockHelpers.randomHash()
    val right: ByteString = BlockHelpers.randomHash()
    val wrong: ByteString = BlockHelpers.randomHash()
    val child: ExecutionPayload = payloadOfUnexecutableChild(invalid)
    whenReady(engineApi.newPayload(child).unsafeToFuture())(_.status shouldBe Accepted)

    engineApi.invalidChainReporter.reportInvalid(invalid, right)
    engineApi.invalidChainReporter.reportInvalid(invalid, wrong)

    engineApi.invalidBlocksSnapshot shouldBe Map(invalid -> right, child.blockHash -> right)

  // ---------------------------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------------------------

  /** Mirrors `ConsensusImplSpec.ConsensusSetup`, but lets each test choose the BlockExecutionError as well as the
    * failing block — classification is the whole point here.
    */
  class ConsensusSetup extends EphemBlockchainTestSetup:
    val reported: mutable.ListBuffer[(ByteString, ByteString)] = mutable.ListBuffer.empty

    lazy val reporterOpt: Option[InvalidChainReporter] = Some { (hash, lvh) =>
      reported += (hash -> lvh); ()
    }

    override lazy val blockExecution: BlockExecution = stub[BlockExecution]

    (blockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .when(*, *, *)
      .anyNumberOfTimes()
      .onCall { (blocks, _, _) =>
        val executed = blocks
          .takeWhile(b => !failingBlockHash.contains(b.hash.value))
          .map(b => BlockData(b, Nil, ChainWeight.zero))
        executed.foreach(b => blockchainWriter.save(b.block, b.receipts, b.weight, false))
        (executed, blocks.find(b => failingBlockHash.contains(b.hash.value)).map(_ => failureError))
      }

    initialChain.foldLeft(ChainWeight.zero) { (previousWeight, block) =>
      val weight = previousWeight.increase(block.header)
      blockchainWriter.save(block, Nil, weight, saveAsBestBlock = true)
      weight
    }

    private var failingBlockHash: Option[ByteString] = None
    private var failureError: BlockExecutionError = ValidationAfterExecError("unset")

    def failAt(block: Block, error: BlockExecutionError): Unit =
      failingBlockHash = Some(block.hash.value)
      failureError = error

    implicit val runtime: IORuntime = IORuntime.global

    lazy val consensusUnderTest: ConsensusImpl =
      new ConsensusImpl(blockchainReader, blockchainWriter, blockExecution, reporterOpt)

    // A live EngineApiService over the SAME storage, so a test can assert what the CL is actually told after the
    // import path speaks. Only the "grandparent" test overrides `reporterOpt` to point at it; for every other test
    // here it is never touched. The stubbed `blockExecution` is safe to hand over: the engine path under test never
    // executes anything (the payload's parent is deliberately absent from storage).
    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi: EngineApiService = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      None
    )(blockchainConfig, typedScheduler)

  /** hive's `Invalid Missing Ancestor Syncing ReOrg, GasUsed, CanonicalReOrg=True`, at the moment the peer delivers the
    * side chain.
    *
    * Canonical 1..15 (post-merge: difficulty 0), executed and weighted, best = 15. Side chain P1'..P9' = 6..14 forking
    * at canonical 5. P8' carries a header gasUsed its own receipts contradict. The CL has already sent P10' (child of
    * P9') via newPayload — ACCEPTED, stored by hash only, indexed under P9' — and an FCU naming it, which took the
    * notify-only path. Everything on the path under test is real: EngineApiService's registry, ForkChoiceManager,
    * ConsensusImpl (reorganise + reportIfProvenInvalid), ConsensusAdapter, and BlockImporter's report rule. Only block
    * execution is stubbed, so the failing block and its error are exact.
    */
  class HiveGasUsedReorgSetup extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global
    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    val zero32: ByteString = ByteString(new Array[Byte](32))

    private def posBlock(block: Block): Block =
      block.copy(
        header = block.header.copy(difficulty = Difficulty.Zero),
        body = block.body.copy(uncleNodesList = Nil)
      )

    private val genesisWeight = ChainWeight.totalDifficultyOnly(BlockHelpers.genesis.header.difficulty.value)
    blockchainWriter.save(BlockHelpers.genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val canonical: List[Block] = BlockHelpers.generateChain(15, BlockHelpers.genesis, posBlock)
    canonical.foldLeft(genesisWeight) { (w, b) =>
      val next = w.increase(b.header)
      blockchainWriter.save(b, Nil, next, saveAsBestBlock = true)
      next
    }

    /** P1'..P9' at heights 6..14, off canonical block 5. */
    val side: List[Block] = BlockHelpers.generateChain(9, canonical(4), posBlock)
    val p7: Block = side(6)
    val p8: Block = side(7)
    val p9: Block = side(8)

    private val gasUsedError: BlockExecutionError = ValidationAfterExecError(
      "Block has invalid gas used, expected 183600 but got 326947; " +
        com.chipprbots.ethereum.consensus.validators.std.StdValidators.HeaderGasContradictsReceiptsMarker
    )

    override lazy val blockExecution: BlockExecution = stub[BlockExecution]
    (blockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .when(*, *, *)
      .anyNumberOfTimes()
      .onCall { (blocks, weight, _) =>
        val ok = blocks.takeWhile(_.hash != p8.hash)
        val executed = ok.foldLeft((weight, List.empty[BlockData])) { case ((w, acc), b) =>
          val next = w.increase(b.header)
          blockchainWriter.save(b, Nil, next, saveAsBestBlock = false)
          (next, acc :+ BlockData(b, Nil, next))
        }
        (executed._2, blocks.find(_.hash == p8.hash).map(_ => gasUsedError))
      }

    lazy val fcm: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

    lazy val engineApi: EngineApiService =
      new EngineApiService(blockchainReader, blockchainWriter, blockExecution, fcm, None)(
        blockchainConfig,
        typedScheduler
      )

    def adapterWith(reporter: Option[InvalidChainReporter]): ConsensusAdapter =
      new ConsensusAdapter(
        new ConsensusImpl(
          blockchainReader,
          blockchainWriter,
          blockExecution,
          reporter,
          Some(DesignatedHead(() => fcm.getRequestedHeadBlockHash))
        ),
        blockchainReader,
        blockQueue,
        new com.chipprbots.ethereum.ledger.BlockValidation(mining, blockchainReader, blockQueue),
        runtime,
        reporter
      )

    /** P10': the CL's head, one hop above the side branch the peer serves. */
    val p10: ExecutionPayload = payloadOfUnexecutableChild(p9.hash.value)

    /** Drive one fetched batch through the production path, then apply BlockImporter's gas-used report rule to the
      * result exactly as `handleBlocksImport` does on that arm: `importedBlocks` is `Nil` for a `BlockImportFailed`, so
      * `notImportedBlocks` is the whole batch, and `findMissingContractCode` finds nothing (the hive recipient's code
      * is present).
      */
    def importBatchThroughTheImporterRule(batch: List[Block]): Unit =
      whenReady(engineApi.newPayload(p10).unsafeToFuture())(_.status shouldBe Accepted)
      whenReady(engineApi.forkchoiceUpdated(ForkChoiceState(p10.blockHash, zero32, zero32), None).unsafeToFuture()) {
        _.map(_.payloadStatus.status) shouldBe Right(Syncing)
      }
      fcm.getRequestedHeadBlockHash shouldBe Some(p10.blockHash)

      val adapter = adapterWith(Some(engineApi.invalidChainReporter))
      val result = adapter.evaluateBranch(NonEmptyList.fromListUnsafe(batch)).unsafeRunSync()

      // The shape BlockImporter sees: a failure it routes to the gas-used arm, naming P8'.
      result shouldBe a[BlockImportFailedAt]
      result.asInstanceOf[BlockImportFailedAt].failingBlockHash shouldBe p8.hash.value
      result.toString should include(InvalidChainReporter.GasUsedMismatchMarker)

      BlockImporter.provenGasUsedFailure(result, batch, adapter.reportsInvalidChains, _ => None).foreach {
        case (failing, descendants) =>
          val lvh = failing.header.parentHash.value
          adapter.reportInvalidChain(failing.hash.value, lvh)
          descendants.foreach(d => adapter.reportInvalidChain(d.hash.value, lvh))
      }

  /** Minimal live EngineApiService. Only the invalid-block registry is exercised, but it is the real one. */
  class EngineSetup extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global

    private val genesis = BlockHelpers.genesis
    blockchainWriter.save(genesis, Nil, ChainWeight.zero, saveAsBestBlock = true)

    lazy val blockExec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new com.chipprbots.ethereum.ledger.BlockValidation(mining, blockchainReader, blockQueue)
    )

    lazy val forkChoiceManager: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi: EngineApiService = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      None
    )(blockchainConfig, typedScheduler)

object InvalidChainReportingSpec:
  val initialChain: List[Block] = BlockHelpers.genesis +: BlockHelpers.generateChain(4, BlockHelpers.genesis)
  val initialBestBlock: Block = initialChain.last

  /** An empty post-Shanghai payload whose parent is `parentHash`. Field-for-field what `payloadToBlock` rebuilds, so
    * the envelope survives newPayload's block-hash integrity check; it is never executed (the parent is deliberately
    * unknown), which is the point — we are testing the registry, not execution.
    */
  def payloadOfUnexecutableChild(parentHash: ByteString): ExecutionPayload =
    val header = BlockHeader(
      parentHash = BlockHash(parentHash),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(BlockHelpers.randomHash()),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHelpers.randomHash()),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(42),
      gasLimit = GasAmount(30000000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1700000000L),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostShanghai(baseFee = BigInt(1000000000), withdrawalsRoot = BlockHeader.EmptyMpt)
    )
    ExecutionPayload(
      parentHash = header.parentHash.value,
      feeRecipient = Address(header.beneficiary),
      stateRoot = header.stateRoot.value,
      receiptsRoot = header.receiptsRoot.value,
      logsBloom = header.logsBloom.value,
      prevRandao = header.mixHash.value,
      blockNumber = header.number.value,
      gasLimit = header.gasLimit.value,
      gasUsed = header.gasUsed.value,
      timestamp = header.unixTimestamp.toLong,
      extraData = header.extraData,
      baseFeePerGas = header.baseFee.getOrElse(BigInt(0)),
      blockHash = header.hash.value,
      transactions = Seq.empty,
      withdrawals = Some(Seq.empty)
    )
