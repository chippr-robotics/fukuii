package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.Tags.*

/** A state that failed closed stays closed across a restart, until it is cleared (B-252; the user's decision of
  * 2026-11-17: persist the fault).
  */
class StateFaultPersistenceSpec extends AnyFlatSpec with Matchers:

  private def reopen(chain: CanonicalChainOnPositions, settings: PathStateStorage.CommitSettings) =
    new PathStateStorage(new PathNodeStorage(chain.source), chain.appState, settings = settings)

  "A state that failed closed" should "open closed again on the same datadir, and give out no state" taggedAs UnitTest in {
    val chain = new CanonicalChainOnPositions()
    val head = chain.refOf(chain.head)
    chain.state.failClosed("a test's corrupt record")
    chain.appState.getStateFault().get should include("a test's corrupt record")

    val restarted = reopen(chain, PathStateStorage.CommitSettings())
    restarted.hasFailedClosed shouldBe true
    restarted.stateAvailable(head) shouldBe false
    (the[StateUnavailable] thrownBy restarted.view(head)).getMessage should include("a test's corrupt record")
  }

  it should "keep the first fault, not a later one" taggedAs UnitTest in {
    val chain = new CanonicalChainOnPositions()
    chain.state.failClosed("the first")
    chain.state.failClosed("the second")
    chain.appState.getStateFault().get should (include("the first").and(not).include("the second"))
  }

  it should "open again once its state is cleared for a sync" taggedAs UnitTest in {
    val chain = new CanonicalChainOnPositions()
    chain.state.failClosed("a test's corrupt record")
    chain.state.clearGenesisStateForSync(chain.refOf(chain.head), atMillis = 1L) shouldBe true

    chain.state.hasFailedClosed shouldBe false
    chain.appState.getStateFault() shouldBe None
    reopen(chain, PathStateStorage.CommitSettings()).hasFailedClosed shouldBe false
  }

  it should "open again once its state is destroyed for a new genesis" taggedAs UnitTest in {
    val settings = PathStateStorage.CommitSettings(allowsStateDestruction = true)
    val chain = new CanonicalChainOnPositions(settings)
    chain.state.failClosed("a test's corrupt record")
    chain.state.destroyStateForNewGenesis() should be > 0

    chain.appState.getStateFault() shouldBe None
    reopen(chain, settings).hasFailedClosed shouldBe false
  }

  "The record of a fault" should "be told as it stands when this build cannot read it, and still close the state" taggedAs UnitTest in {
    StateFaultMark.describe(StateFaultMark.encode(0L, "why: with a colon")) should startWith(
      "why: with a colon (found at "
    )
    StateFaultMark.describe("v9:something") shouldBe "a fault recorded as 'v9:something'"

    val chain = new CanonicalChainOnPositions()
    chain.appState.put(StateFaultMark.Key, "v9:something").commitSync()
    reopen(chain, PathStateStorage.CommitSettings()).hasFailedClosed shouldBe true
  }
