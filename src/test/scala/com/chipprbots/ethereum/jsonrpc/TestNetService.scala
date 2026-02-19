package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.jsonrpc.NetService._

/** Manual test double for NetServiceAPI.
  *
  * Replaces ScalaMock `mock[NetServiceAPI]` because ScalaMock 6.0.0 with Scala 3.3.4 generates inconsistent internal
  * method name suffixes when mock creation and `.expects()` occur in different compilation units.
  *
  * Usage: assign the `*Fn` var for each method that the test exercises. Unassigned methods raise NotImplementedError.
  */
class TestNetService extends NetServiceAPI {

  var versionFn: VersionRequest => ServiceResponse[VersionResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.version not configured"))

  var listeningFn: ListeningRequest => ServiceResponse[ListeningResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.listening not configured"))

  var peerCountFn: PeerCountRequest => ServiceResponse[PeerCountResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.peerCount not configured"))

  var nodeInfoFn: NodeInfoRequest => ServiceResponse[NodeInfoResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.nodeInfo not configured"))

  var listPeersFn: ListPeersRequest => ServiceResponse[ListPeersResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.listPeers not configured"))

  var disconnectPeerFn: DisconnectPeerRequest => ServiceResponse[DisconnectPeerResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.disconnectPeer not configured"))

  var connectToPeerFn: ConnectToPeerRequest => ServiceResponse[ConnectToPeerResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.connectToPeer not configured"))

  var listBlacklistedPeersFn: ListBlacklistedPeersRequest => ServiceResponse[ListBlacklistedPeersResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.listBlacklistedPeers not configured"))

  var addToBlacklistFn: AddToBlacklistRequest => ServiceResponse[AddToBlacklistResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.addToBlacklist not configured"))

  var removeFromBlacklistFn: RemoveFromBlacklistRequest => ServiceResponse[RemoveFromBlacklistResponse] =
    _ => IO.raiseError(new NotImplementedError("TestNetService.removeFromBlacklist not configured"))

  override def version(req: VersionRequest): ServiceResponse[VersionResponse] = versionFn(req)
  override def listening(req: ListeningRequest): ServiceResponse[ListeningResponse] = listeningFn(req)
  override def peerCount(req: PeerCountRequest): ServiceResponse[PeerCountResponse] = peerCountFn(req)
  override def nodeInfo(req: NodeInfoRequest): ServiceResponse[NodeInfoResponse] = nodeInfoFn(req)
  override def listPeers(req: ListPeersRequest): ServiceResponse[ListPeersResponse] = listPeersFn(req)
  override def disconnectPeer(req: DisconnectPeerRequest): ServiceResponse[DisconnectPeerResponse] = disconnectPeerFn(req)
  override def connectToPeer(req: ConnectToPeerRequest): ServiceResponse[ConnectToPeerResponse] = connectToPeerFn(req)
  override def listBlacklistedPeers(req: ListBlacklistedPeersRequest): ServiceResponse[ListBlacklistedPeersResponse] = listBlacklistedPeersFn(req)
  override def addToBlacklist(req: AddToBlacklistRequest): ServiceResponse[AddToBlacklistResponse] = addToBlacklistFn(req)
  override def removeFromBlacklist(req: RemoveFromBlacklistRequest): ServiceResponse[RemoveFromBlacklistResponse] = removeFromBlacklistFn(req)
}
