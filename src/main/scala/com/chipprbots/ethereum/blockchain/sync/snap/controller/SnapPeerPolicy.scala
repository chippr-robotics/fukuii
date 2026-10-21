package com.chipprbots.ethereum.blockchain.sync.snap.controller

/** Pure SNAP peer-eligibility decisions (spec 016 M3): which handshaked peers may serve SNAP state, and why a
  * snap-capable peer is left out. Re-exported by `object SNAPSyncController`, so `SyncController` and the tests call
  * them unchanged.
  */
private[snap] object SnapPeerPolicy:

  /** Whether a handshaked peer can serve SNAP state for our pivot: SNAP-capable, on our fork, and not sitting at its
    * genesis block. Every phase that hands peers to a coordinator filters on this: account ranges, bytecodes, storage
    * ranges, trie-node healing, and the post-sync recovery in `SyncController`. A peer at genesis holds no state and
    * answers every request empty, which costs each phase strikes or retries (#1356). On ETC that is typically an
    * ETH-mainnet node syncing from scratch: the chains share genesis and networkId 1, so it passes the fork-ID check.
    *
    * The test is the peer's best-block hash. It is known from the handshake on for every protocol version, and it moves
    * with `maxBlockNumber` whenever the peer reports a higher block (`withBestBlockData`). It used to be
    * `maxBlockNumber > 0` (#1356), but an eth/68 STATUS carries no block number, so every eth/68 peer read as block 0
    * until a header probe landed. That kept it out of the storage pool for up to the 5-minute re-probe interval, which
    * in a 2-peer ETC pool is the whole pool. Unlike the `>= pivot` guard removed in PR #1238, this does not exclude a
    * peer that is merely behind: one at block N < pivot still serves state up to N.
    */
  private[sync] def servesSnapState(
      peerInfo: com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
  ): Boolean =
    peerInfo.remoteStatus.supportsSnap &&
      peerInfo.forkAccepted &&
      peerInfo.bestBlockHash != peerInfo.remoteStatus.genesisHash

  /** Why a snap-capable handshaked peer is NOT handed to the SNAP coordinators, or `None` if it is (or if it does not
    * advertise snap at all — those are not "excluded", they never qualified). Mirrors the two filters applied in order:
    * `PeerListHelper.peersToDownloadFrom` (fork accepted, not blacklisted) and [[servesSnapState]] (best block is not
    * genesis). Diagnostic only; the selection itself still goes through those two functions.
    *
    * On the genesis clause: `bestBlockHash` is seeded from the STATUS best/latest hash on every protocol version —
    * eth/68 `bestHash`, eth/69 and eth/70 `latestBlockHash` — and moves forward on `BlockRangeUpdate`, `NewBlock`,
    * `NewBlockHashes` and header responses (`NetworkPeerManagerActor.updateMaxBlock`). So a peer reads as "at genesis"
    * only when it itself reported genesis as its head, which is what a node that has not finished its own sync (e.g. a
    * snap-syncing geth) advertises. It cannot serve state, and excluding it is correct.
    *
    * @param blacklistReason
    *   the sync blacklist's reason for this peer, if blacklisted
    */
  private[sync] def snapExclusionReason(
      peerInfo: com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo,
      blacklistReason: Option[String]
  ): Option[String] =
    if !peerInfo.remoteStatus.supportsSnap then None
    else if !peerInfo.forkAccepted then Some("fork-not-accepted")
    else
      blacklistReason match
        case Some(reason) => Some(s"blacklisted($reason)")
        case None if peerInfo.bestBlockHash == peerInfo.remoteStatus.genesisHash =>
          Some(SnapExclusionAtGenesis)
        case None => None

  /** Exclusion reason for a peer whose best block is its genesis. A stable key: the `[SNAP-PEERS]` change detection
    * compares reasons, so it must not embed a moving value such as the block number (printed separately).
    */
  private[snap] val SnapExclusionAtGenesis: String = "best-block-is-genesis"

  /** Steady-state cadence of the `[SNAP-PEERS]` exclusion log while some snap-capable peer is excluded. */
  private[snap] val SnapExclusionLogIntervalMs: Long = 60_000L

  /** Minimum spacing of `[SNAP-PEERS]` lines triggered by a change in the excluded set (peer churn guard). */
  private[snap] val SnapExclusionMinLogIntervalMs: Long = 10_000L
