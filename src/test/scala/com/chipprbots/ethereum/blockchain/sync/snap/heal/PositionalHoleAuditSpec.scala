package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.CachingMptStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.NodeFault
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairFixture
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*

/** The positional-hole caveat (spec 018 data-model 6.5; C5b, T215). The decoded-node cache is keyed by hash, and
  * identical nodes occupy many positions, so a hit by hash can hide a row absent at one position while another position
  * holds the same node. Execution does not trip over it; the audit reads the rows directly, and finds it.
  */
class PositionalHoleAuditSpec extends AnyFlatSpec with Matchers:

  "A cached node" should "hide a hole at one position from execution, and not from the audit's direct read" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    // bob and alice hold the same storage trie, node for node
    val (bobRow, node) = f.innerRow(Some(owner(bob)))
    val aliceRow = PositionKey(Some(owner(alice)), bobRow.hp)
    val hash = f.hashOf(node)
    f.rowAt(aliceRow).get shouldBe node
    val cache = DecodedNodeCache.withOwnBudget(1L << 20)
    def execution(of: ByteString) = new CachingMptStorage(f.state.readOnly(f.anchor), cache).forOwner(Some(of))

    // execution reads the node at alice's position, which puts it in the cache by hash
    execution(owner(alice)).getAt(aliceRow.path, hash.toArray).encode shouldBe node
    f.remove(bobRow)

    withClue("a read of the same node at bob's position is a hit: ") {
      execution(owner(bob)).getAt(bobRow.path, hash.toArray).encode shouldBe node
    }
    withClue("without the cache, the row is a hole: ") {
      a[MissingNodeException] should be thrownBy f.state
        .readOnly(f.anchor)
        .forOwner(Some(owner(bob)))
        .getAt(
          bobRow.path,
          hash.toArray
        )
    }
    withClue("the audit's read bypasses the cache and finds the hole: ") {
      f.state.rawRowsAt(Seq(bobRow -> hash)) shouldBe Seq(RawNodeRead.Absent)
      val holes = new PositionAuditHarness(f).walkAll().holes
      holes.map(h => (h.position.account, h.hash, h.fault)) shouldBe Seq((Some(owner(bob)), hash, NodeFault.Absent))
    }
  }
