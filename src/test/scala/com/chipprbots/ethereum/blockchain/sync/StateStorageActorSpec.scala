package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern._
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.SyncState
import com.chipprbots.ethereum.blockchain.sync.fast.StateStorageActor
import com.chipprbots.ethereum.blockchain.sync.fast.StateStorageActor.GetStorage
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage

class StateStorageActorSpec
    extends TestKit(ActorSystem("FastSyncStateActorSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with Eventually
    with NormalPatience {

  "FastSyncStateActor" should "eventually persist a newest state of a fast sync" taggedAs (UnitTest, SyncTest) in {
    val dataSource = EphemDataSource()
    val syncStateActor = TestActorRef(new StateStorageActor)
    val maxN = 10

    val targetBlockHeader = Fixtures.Blocks.ValidBlock.header
    syncStateActor ! new FastSyncStateStorage(dataSource)
    (0 to maxN).foreach(n => syncStateActor ! SyncState(targetBlockHeader).copy(downloadedNodesCount = n))

    eventually {
      (syncStateActor ? GetStorage)
        .mapTo[Option[SyncState]]
        .map { syncState =>
          val expected = SyncState(targetBlockHeader).copy(downloadedNodesCount = maxN)
          syncState shouldEqual Some(expected)
        }(system.dispatcher)
    }
  }
}
