package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader

/** The controller's ChainDownloader child (spec 016 P1, FR-010), shared by finalization and pivot refresh.
  *
  * It is separate from the four coordinators on purpose: the downloader survives restarts and dormancy, so
  * `CoordinatorHandles.stopAll()` never stops it. Only the finalization paths call `stop()`.
  */
final private[snap] class ChainDownloaderHandle(stopChild: TypedActorRef[Nothing] => Unit):

  private var ref: Option[TypedActorRef[ChainDownloader.Command]] = None

  def isDefined: Boolean = ref.isDefined

  def isEmpty: Boolean = ref.isEmpty

  /** Record a freshly spawned downloader. */
  def attach(downloader: TypedActorRef[ChainDownloader.Command]): Unit =
    ref = Some(downloader)

  /** Send `msg` if a downloader exists; otherwise do nothing. */
  def tell(msg: ChainDownloader.Command): Unit =
    ref.foreach(_ ! msg)

  /** Stop the downloader, if any, and forget it. */
  def stop(): Unit =
    ref.foreach(stopChild)
    ref = None
