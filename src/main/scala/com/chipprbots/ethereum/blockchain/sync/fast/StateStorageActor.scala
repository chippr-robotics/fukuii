package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.pattern.pipe

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.SyncState
import com.chipprbots.ethereum.blockchain.sync.fast.StateStorageActor.GetStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage

/** Persists current state of fast sync to a storage. Can save only one state at a time. If during persisting new state
  * is received then it will be saved immediately after current state was persisted. If during persisting more than one
  * new state is received then only the last state will be kept in queue.
  */
class StateStorageActor extends Actor with ActorLogging {

  def receive: Receive = {
    // after initialization send a valid Storage reference
    case storage: FastSyncStateStorage => context.become(idle(storage))
  }

  def idle(storage: FastSyncStateStorage): Receive = {
    // begin saving of the state to the storage and become busy
    case state: SyncState => persistState(storage, state)

    case GetStorage => sender() ! storage.getSyncState()
  }

  def busy(storage: FastSyncStateStorage, stateToPersist: Option[SyncState]): Receive = {
    // update state waiting to be persisted later. we only keep newest state
    case state: SyncState => context.become(busy(storage, Some(state)))
    // exception was thrown during persisting of a state. push
    case Failure(e) => throw e
    // state was saved in the storage. become idle
    case Success(s: FastSyncStateStorage) if stateToPersist.isEmpty => context.become(idle(s))
    // state was saved in the storage but new state is already waiting to be saved.
    case Success(s: FastSyncStateStorage) if stateToPersist.isDefined => stateToPersist.foreach(persistState(s, _))

    case GetStorage => sender() ! storage.getSyncState()
  }

  private def persistState(storage: FastSyncStateStorage, syncState: SyncState): Unit = {
    implicit val runtime: IORuntime = IORuntime.global

    val persistingQueues: IO[Try[FastSyncStateStorage]] = IO {
      lazy val result = Try(storage.putSyncState(syncState))
      if (log.isDebugEnabled) {
        val now = System.currentTimeMillis()
        result
        val end = System.currentTimeMillis()
        log.debug(s"Saving snapshot of a fast sync took ${end - now} ms")
        result
      } else {
        result
      }
    }
    persistingQueues.unsafeToFuture().pipeTo(self)
    context.become(busy(storage, None))
  }

}

object StateStorageActor {
  case object GetStorage
}
