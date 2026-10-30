package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.VerificationBFSComplete
import com.chipprbots.ethereum.blockchain.sync.snap.heal.PathPresence
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.MptNode

/** The coordinator state `HealVerification` reads and writes (spec 016 C-H3, research.md R14b). The core's behaviour
  * arms (`healVerificationArms`, the completion gate) and the code C-H4 and C-H5 will move touch them too, or rule (d)
  * keeps them in the core, so the Impl keeps them as fields. `reverifyCurrentRoot` clears `verificationPassComplete`
  * (the walk launcher it calls sets `verificationBFSRunning`, and only when the walk starts: spec 022 review hunter
  * W1); they read the walk root `stateRoot` and the completion gate's `flushing` and `trieWalkInProgress`.
  */
private[actors] trait HealVerificationState:
  var stateRoot: ByteString
  var verificationBFSRunning: Boolean
  var verificationPassComplete: Boolean
  var trieWalkInProgress: Boolean
  def flushing: Boolean

/** What the frontier-rebuild walk calls for a Path-scheme read: `readPathVerifiedNode`, the bytes at the entry's own
  * path, counted present only when they hash to the entry's hash. `HealVerification` provides it (spec 016 C-H3).
  */
private[actors] trait PathVerifiedReadApi:
  def readPathVerifiedNode(hash: ByteString, pathset: Seq[ByteString]): Option[MptNode]

/** What the core's presence check (`isNodeInStorage`, C-H5's) calls for a Path-scheme read: `readPathVerifiedRlp`, the
  * raw bytes at the entry's own path, returned only when they hash to the entry's hash. `HealVerification` provides it
  * (spec 016 C-H3). A separate trait, so the C-H2 interface stays as recorded.
  */
private[actors] trait PathVerifiedRlpApi:
  def readPathVerifiedRlp(hash: ByteString, pathset: Seq[ByteString]): Option[Array[Byte]]

/** What the response processing's Path-scheme child discovery calls (spec 022 FR-008): `pathPresence`, which tells an
  * absent position from one holding a stale node with the wrong hash, so the write that references the position can
  * delete the stale bytes in its own batch. `HealVerification` provides it, over the same read as
  * `readPathVerifiedRlp`. A separate trait, so the C-H2 and C-H3 interfaces stay as recorded.
  */
private[actors] trait PathPresenceApi:
  def pathPresence(hash: ByteString, pathset: Seq[ByteString]): PathPresence

/** TrieNodeHealingCoordinator's heal verification (spec 016 C-H3, coordinator-characterization.md §3.4): the
  * verification entry points that re-walk the trie before the heal may complete — `reverifyCurrentRoot` (discard the
  * last verdict and walk the current root again) and `startVerificationBFS` (the full-root walk), both on the
  * frontier-rebuild walk's launchers and completing through `VerificationBFSComplete`; and the Path-scheme verified
  * reads `readPathVerifiedRlp` and `readPathVerifiedNode` (the bytes at an entry's own path, counted present only when
  * they hash to the entry's hash), which the walk and the core's presence check call. It writes no store. The
  * coordinator core keeps the behaviour arms that call it and handle the verdict (`healVerificationArms`, the
  * completion gate).
  */
private[actors] trait HealVerification extends PathVerifiedReadApi with PathVerifiedRlpApi with PathPresenceApi:
  this: HealVerificationState & TrieNodeHealingEnv & FrontierRebuildApi & HealCompletionApi & WalkFlushApi =>

  /** Discard the verdict of the last verification pass and walk the CURRENT root again. Used when that pass was run
    * against a superseded root or found missing nodes. Clears that verdict and starts the walk directly when nothing is
    * pending or in flight; otherwise the completion check walks once the work drains (`verificationPassComplete ==
    * false`: the check completes only on a clean pass, spec 022 review hunter-r3 C1). The walk itself starts only once
    * the flush buffer is written (`startVerificationBFS`, spec 022 review forge F1); a buffer that holds anything means
    * something was healed, so the gate that retries it walks too.
    */
  def reverifyCurrentRoot(): Unit =
    verificationPassComplete = false
    if isComplete && !flushing && !trieWalkInProgress && !verificationBFSRunning then
      startVerificationBFS(
        stateRoot,
        ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      )
    else self ! HealingCheckCompletion

  /** Start a verification BFS (see [[startFrontierBFS]]).
    *
    * Traverses all locally-held trie nodes starting at `root` / `rootPath` and queues any missing descendants as
    * `FrontierRebuilt` messages. Sends `VerificationBFSComplete` when done.
    *
    * Needed because `discoverMissingChildren` skips storage roots that are already in local storage without recursing
    * into their children — a storage sub-trie with a locally-held root can still have gaps deeper in the tree (RUN10:
    * account 888157b2 had 11 missing storage nodes after StateHeal declared completion). BFS catches every missing
    * descendant in O(levels × chunk_reads) instead of O(total_nodes) point-reads.
    */
  def startVerificationBFS(root: ByteString, rootPath: ByteString): Unit =
    if walkMayStart(s"Verification walk of root ${Hex.toHexString(root.take(4).toArray)}") then
      val selfRef = self
      startFrontierBFS(
        root,
        rootPath,
        isStor = false,
        (walk: Long, missing: Long) => selfRef ! VerificationBFSComplete(root, missing, walk)
      )

  /** spec 022 review forge F1: a frontier walk starts only on an empty flush buffer, or once a synchronous flush has
    * written it (`flushedForWalk`). Every verification walk starts here: the completion gate, a re-verification after a
    * dirty or stale pass, a re-peg onto a root already on disk, and the dead-pulse watchdog. A walk over an unwritten
    * buffer reads the buffered nodes as missing and is refused their fetch (they are complete), so it ends dirty, and
    * the next walk would too, with nothing writing the buffer: a stall no re-peg ends (the walk keeps `walkLocalOnly`
    * set). When the write fails, no walk starts; the completion check retries the write at once and the 2-minute pulse
    * after that, and the check's own walk follows a write that succeeds (the check completes only on a clean pass, so
    * with none it walks).
    */
  private def walkMayStart(what: String): Boolean =
    if flushedForWalk() then true
    else
      log.warn(
        s"[HEAL-VERIFY] $what not started: healed nodes waiting in the flush buffer could not be written, and a walk " +
          "would read them as missing. It starts once they are written (retried by the completion check and the pulse)."
      )
      self ! HealingCheckCompletion
      false

  /** Path scheme only: the bytes stored at the task's OWN path in [[PathNodeStorage]], returned ONLY when their
    * keccak256 equals `hash` — otherwise `None`.
    *
    * `pathset` is the same shape `HealingEntry` / `GetTrieNodes` use, HP/compact-encoded: `Seq(compactPath)` for an
    * account-trie node, `Seq(accountHash32, compactStoragePath)` for a storage-trie node. It is decoded to the nibble
    * path `PathNodeStorage.readAccountNode` / `readStorageNode` take (they HP-encode it back to the RocksDB key).
    *
    * Why the hash comparison is mandatory: `PathNodeStorage` keeps exactly ONE node per path slot, and a re-peg REUSES
    * paths — a node healed against the previous root can sit at the very path the new root needs, with DIFFERENT
    * content. "Something is stored at this path" is therefore NOT presence; only "the bytes at this path are the bytes
    * whose keccak256 the parent references" is. A stale node, or the right bytes at the wrong path, both fall through
    * to `None` and get re-fetched (an idempotent overwrite).
    *
    * Also `None`: no `PathNodeStorage` configured, nothing stored at the path, or a pathset of an unexpected shape (no
    * path context ⇒ conservatively absent, never present).
    */
  def readPathVerifiedRlp(hash: ByteString, pathset: Seq[ByteString]): Option[Array[Byte]] =
    storedAtPath(pathset).filter(rlp => ByteString.fromArrayUnsafe(kec256(rlp)) == hash)

  /** Path scheme only (spec 022 FR-008; geth `trie/sync.go` `hasNode`): `Present` when the bytes stored at `pathset`'s
    * own position hash to `hash`, `Mismatch` when bytes are stored there but hash to something else (a stale node from
    * an older root), `Absent` when nothing is stored there — and also with no `PathNodeStorage` or a pathset of an
    * unexpected shape, exactly where `readPathVerifiedRlp` returns `None`. `Present` iff `readPathVerifiedRlp` is
    * defined: one read, the same answer.
    */
  def pathPresence(hash: ByteString, pathset: Seq[ByteString]): PathPresence =
    storedAtPath(pathset) match
      case None                                                         => PathPresence.Absent
      case Some(rlp) if ByteString.fromArrayUnsafe(kec256(rlp)) == hash => PathPresence.Present
      case Some(_)                                                      => PathPresence.Mismatch

  /** The bytes stored at `pathset`'s own position in [[PathNodeStorage]], unverified (see `readPathVerifiedRlp`). */
  private def storedAtPath(pathset: Seq[ByteString]): Option[Array[Byte]] =
    import com.chipprbots.ethereum.mpt.HexPrefix
    stateIngest.pathNodes.flatMap { pns =>
      pathset match
        case Seq(compactPath) =>
          pns.readAccountNode(HexPrefix.decode(compactPath.toArray)._1)
        case Seq(accountHash, compactPath) =>
          pns.readStorageNode(accountHash, HexPrefix.decode(compactPath.toArray)._1)
        case _ => None
    }

  /** `readPathVerifiedRlp` plus decode, for the frontier-rebuild walk, which must traverse the node's children. Bytes
    * that hash correctly but do not decode are reported absent (so they are re-fetched and overwritten) and logged: it
    * should be unreachable — a node referenced by a valid trie decodes — but must never be counted present.
    */
  def readPathVerifiedNode(
      hash: ByteString,
      pathset: Seq[ByteString]
  ): Option[com.chipprbots.ethereum.mpt.MptNode] =
    readPathVerifiedRlp(hash, pathset).flatMap { rlp =>
      try Some(MptStorage.decodeNode(rlp, hash.toArray))
      catch
        case scala.util.control.NonFatal(e) =>
          asyncLog.warn(
            s"[HEAL-BFS] Path-scheme node ${Hex.toHexString(hash.take(4).toArray)} hashes correctly at its path but " +
              s"does not decode (${e.getMessage}) — treating as missing so it is re-fetched"
          )
          None
    }
