package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SnapPeerHealth
import com.chipprbots.ethereum.testing.Tags.*

/** The storage eligible-set floor must not keep reviving a peer that never answers (Sepolia 2026-10-07: 230 revivals of
  * a peer with 0 of 386 requests answered).
  */
class StorageFloorSelectionSpec extends AnyFlatSpec with Matchers:

  import StorageRangeCoordinator.FloorPick

  final private case class P(id: String, cooling: Boolean, coolUntil: Long)

  private def pick(servable: List[P], health: SnapPeerHealth, now: Long = 0L): Option[FloorPick[P]] =
    StorageRangeCoordinator.selectFloorPeer[P](
      servable,
      isCooling = _.cooling,
      isPenalised = p => health.isPenalised(p.id, now),
      cooldownUntilMs = _.coolUntil,
      penaltyUntilMs = p => health.penaltyUntilMs(p.id)
    )

  private def demote(h: SnapPeerHealth, id: String, atMs: Long): Unit =
    (1 to h.timeoutThreshold).foreach(_ => h.recordTimeout(id, atMs))

  "selectFloorPeer" should "revive the soonest-cooling peer when nobody is penalised" taggedAs UnitTest in {
    val a = P("a", cooling = true, coolUntil = 500)
    val b = P("b", cooling = true, coolUntil = 100)
    pick(List(a, b), new SnapPeerHealth()) shouldBe Some(FloorPick.Revive(b))
  }

  it should "skip a penalised peer even when its cooldown expires first" taggedAs UnitTest in {
    val dead = P("dead", cooling = true, coolUntil = 1) // the one the old floor always picked
    val live = P("live", cooling = true, coolUntil = 4000)
    val h = new SnapPeerHealth()
    demote(h, "dead", 0L)
    pick(List(dead, live), h) shouldBe Some(FloorPick.Revive(live))
  }

  it should "probe a penalised peer only when no other servable peer exists" taggedAs UnitTest in {
    val dead1 = P("dead1", cooling = true, coolUntil = 1)
    val dead2 = P("dead2", cooling = false, coolUntil = 0)
    val h = new SnapPeerHealth()
    demote(h, "dead1", 0L)
    demote(h, "dead2", 60000L) // penalised later, so its penalty lapses later
    pick(List(dead1, dead2), h, now = 60001L) shouldBe Some(FloorPick.LastResortProbe(dead1))
  }

  it should "pick nothing when there is no servable peer" taggedAs UnitTest in {
    pick(Nil, new SnapPeerHealth()) shouldBe None
  }
