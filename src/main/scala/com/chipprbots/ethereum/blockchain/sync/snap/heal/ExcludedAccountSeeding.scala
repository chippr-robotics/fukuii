package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode

/** Spec 022 D5, the heal side of the durable excluded set: resolves each entry against the current root, in pages.
  *
  * An account in the set was committed to the account trie while its storage (or code) was left incomplete, so it must
  * not count as complete until proven otherwise. For each entry [[step]] looks the account up from `root`, reading the
  * store only:
  *   - the store shows the current root holds no such account: the entry goes dormant. The decision still stands for
  *     the account's storage, and a later root can hold it again (A to B to A, a reorg; review hunter W2). A dormant
  *     entry does not hold completion, except that each new root has to look at it once ([[blocking]]);
  *   - the lookup hits a node that is not stored: that node is a seed. On a datadir that keeps the invariant it is
  *     already being healed (the seed joins its request); on one that does not, it is what no discovery would reach;
  *   - the account's storage root or code is absent: a seed, or a code hash to fetch;
  *   - both are present: the entry leaves the set (under Path "present" means right hash at the right position, and by
  *     the invariant a present node's subtree is complete; under Hash with `pruning.mode = inmemory` a node read as
  *     present may be only in the node cache, so a crash can take it back after the entry is gone: one of the reasons
  *     the walk stays there, T051 and T052).
  *
  * A dormant entry a later root brings back counts again, with its seeds, but gets no Path position delete: those run
  * only at heal start ([[deleteAccountPositions]]). That costs the structural guarantee for that account, not the
  * resolution.
  *
  * A code-only entry has no account to look up (the bytecode download knows only the hash): it leaves when the code is
  * stored. Nothing here decides that healing is complete; the caller's gate does.
  *
  * Resolving holds no entry in memory beyond a page, and a page is read and resolved on the caller's thread: `pageSize`
  * bounds both the time the coordinator's thread is taken and the seeds a step can add to its queue. A read that fails
  * leaves its entry as it is, for the next pass. A change to an entry is durable before the step returns.
  *
  * Actor-confined: not thread-safe.
  */
final class ExcludedAccountSeeding(
    store: SnapSyncProgressStorage,
    readNode: (ByteString, Seq[ByteString]) => Option[MptNode],
    codePresent: ByteString => Boolean,
    pageSize: Int = ExcludedAccountSeeding.DefaultPageSize,
    // Whether a seed's node is already here and only not on disk yet: held, or in the flush buffer. A read goes to the
    // store only, so such a node looks absent, but it is no fetch to make and no sign that anything but its chain is
    // wanted. The next pass after the chain is written reads the account itself.
    seedNeedsNoFetch: ((Seq[ByteString], ByteString)) => Boolean = _ => false
):
  import ExcludedAccountSeeding.*

  // Where the pass stands, as the last entry it has resolved; none at the start of a pass.
  private var cursor: Option[ByteString] = None
  private var changedInPass: Boolean = false
  private var seedsInPass: Int = 0
  private var failedReadsInPass: Int = 0
  // What the last pass to reach its end found, since the root last changed: (seeds, failed reads).
  private var lastPass: Option[(Int, Int)] = None

  /** Start the next [[step]] at the first entry: the root changed. */
  def restart(): Unit =
    cursor = None
    changedInPass = false
    seedsInPass = 0
    failedReadsInPass = 0
    lastPass = None

  /** Whether the durable set holds anything that counts against a root. */
  def outstanding: Boolean = store.hasExcluded

  /** Whether the set stands against the current root: an entry that counts, or a dormant one no pass has looked at from
    * this root yet (it may hold the account).
    */
  def blocking: Boolean = store.hasExcluded || (lastPass.isEmpty && store.hasAnyExcluded)

  /** Whether a pass has reached its end since the root changed. */
  def passEnded: Boolean = lastPass.isDefined

  /** Whether a pass is part way through its pages. */
  def midPass: Boolean = cursor.isDefined

  /** Whether the last pass to end at this root found nothing to seed and read everything. */
  def lastPassQuiet: Boolean = lastPass.exists { case (seeds, failedReads) => seeds == 0 && failedReads == 0 }

  /** Whether all the set still wants at the current root is bytecode: a pass has reached its end at this root, found
    * nothing to seed and read everything, no later pass is part way through, and an entry still counts. Unknown is
    * false.
    */
  def onlyCodeOutstanding: Boolean = !midPass && lastPassQuiet && store.hasExcluded

  /** Record `codeHashes` as bytecode the held chains are going to be written without (plan D4). Durable when it
    * returns.
    */
  def recordMissingCode(codeHashes: Seq[ByteString]): Unit =
    store.recordExcluded(codeHashes.map(ExcludedAccount(_, ExcludedReason.CodeMissing)))

  /** Hand `delete` the positions of every account in the set (T034), a batch at a time: every position of an account in
    * one batch, each position once. Returns how many accounts there were. Throws if a page cannot be read or a batch
    * cannot be written; what was deleted stays deleted, which is safe, and a later run deletes the rest.
    */
  def deleteAccountPositions(delete: Seq[(Option[ByteString], Array[Byte])] => Unit): Int =
    @tailrec
    def loop(after: Option[ByteString], accounts: Int): Int =
      val page = store.excludedPage(after, DeleteBatchAccounts)
      val keys = page.filterNot(e => e.isCodeOnly || e.dormant).map(_.subject)
      if keys.nonEmpty then delete(keys.flatMap(accountPathPositions).distinctBy(p => ByteString(p._2)))
      if page.size == DeleteBatchAccounts then loop(page.lastOption.map(_.cursor), accounts + keys.size)
      else accounts + keys.size
    loop(None, 0)

  /** Resolve the next page against `root`. Throws only if the page cannot be read or its changes cannot be written, and
    * then changes nothing here: the same page is resolved again.
    */
  def step(root: ByteString): Step =
    val page = store.excludedPage(cursor, pageSize)
    val seeds = mutable.LinkedHashSet.empty[(Seq[ByteString], ByteString)]
    val code = mutable.LinkedHashSet.empty[ByteString]
    val complete = mutable.ArrayBuffer.empty[ExcludedAccount]
    val toDormant = mutable.ArrayBuffer.empty[ExcludedAccount]
    val toActive = mutable.ArrayBuffer.empty[ExcludedAccount]
    var failedReads = 0
    page.foreach { entry =>
      try
        resolve(root, entry) match
          case Resolution.Complete                => complete += entry
          case Resolution.Absent if entry.dormant => ()
          case Resolution.Absent                  => toDormant += entry
          case Resolution.Needs(ns, cs) =>
            if entry.dormant then toActive += entry
            seeds ++= ns.filterNot(seedNeedsNoFetch)
            code ++= cs
      catch
        case NonFatal(e) =>
          failedReads += 1
          log.warn(s"[HEAL-EXCLUDED] Could not resolve ${hex(entry.subject)}, kept for the next pass: ${e.getMessage}")
    }
    store.updateExcluded(
      (complete ++ toDormant ++ toActive).toSeq,
      toDormant.map(_.copy(dormant = true)).toSeq ++ toActive.map(_.copy(dormant = false))
    )
    val more = page.size == pageSize
    val changed = changedInPass || complete.nonEmpty || toDormant.nonEmpty || toActive.nonEmpty
    val seededInPass = seedsInPass + seeds.size
    val failedInPass = failedReadsInPass + failedReads
    cursor = if more then page.lastOption.map(_.cursor) else None
    changedInPass = more && changed
    seedsInPass = if more then seededInPass else 0
    failedReadsInPass = if more then failedInPass else 0
    if !more then lastPass = Some((seededInPass, failedInPass))
    Step(seeds.toSeq, code.toSeq, complete.size, toDormant.size, toActive.size, failedReads, more, changed)

  private def resolve(root: ByteString, entry: ExcludedAccount): Resolution =
    if entry.isCodeOnly then
      if entry.subject == Account.EmptyCodeHash.value || codePresent(entry.subject) then Resolution.Complete
      else Resolution.Needs(Nil, Seq(entry.subject))
    else
      lookup(root, entry.subject, readNode) match
        case Lookup.Absent              => Resolution.Absent
        case Lookup.Missing(path, hash) => Resolution.Needs(Seq((path, hash)), Nil)
        case Lookup.Found(account) =>
          val storageSeed = Option
            .when(account.storageRoot != Account.EmptyStorageRootHash) {
              (Seq(entry.subject, EmptyPath), account.storageRoot.value)
            }
            .filter { case (path, hash) => readNode(hash, path).isEmpty }
          val missingCode = Option
            .when(account.codeHash != Account.EmptyCodeHash)(account.codeHash.value)
            .filterNot(codePresent)
          if storageSeed.isEmpty && missingCode.isEmpty then Resolution.Complete
          else Resolution.Needs(storageSeed.toSeq, missingCode.toSeq)

object ExcludedAccountSeeding:
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[ExcludedAccountSeeding])

  /** Entries resolved per step. About 10 node reads each at worst, so a few seconds of the actor's thread at the very
    * worst on a cold SATA disk, and a queue no longer than this per step.
    */
  val DefaultPageSize: Int = 256

  /** How many accounts a heal start's position deletes (T034) carry in one write batch. Every position of one account
    * stays in one batch; 128 accounts × 65 positions is at most about 8k keys, the size `storage-rocksdb.md` names.
    */
  val DeleteBatchAccounts: Int = 128

  private val EmptyPath: ByteString = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  /** What one [[ExcludedAccountSeeding.step]] did. `seeds`: heal tasks to queue, `(pathset, hash)`; `missingCode`: code
    * hashes to fetch; `removed`: entries now out of the set; `demoted`: entries now dormant (this root holds no such
    * account); `promoted`: dormant entries that count again; `failedReads`: entries that could not be resolved; `more`:
    * the pass has further pages; `changed`: some step of this pass changed an entry.
    */
  final case class Step(
      seeds: Seq[(Seq[ByteString], ByteString)],
      missingCode: Seq[ByteString],
      removed: Int,
      demoted: Int,
      promoted: Int,
      failedReads: Int,
      more: Boolean,
      changed: Boolean
  )

  private enum Resolution:
    case Complete
    case Absent
    case Needs(seeds: Seq[(Seq[ByteString], ByteString)], code: Seq[ByteString])

  enum Lookup:
    /** The root holds no such account: the walk reached a node that excludes its key. */
    case Absent

    /** The node at `pathset`, of hash `hash`, is not stored. */
    case Missing(pathset: Seq[ByteString], hash: ByteString)

    case Found(account: Account)

  /** The 64 nibbles of an account hash. */
  def accountNibbles(accountHash: ByteString): Array[Byte] =
    accountHash.iterator.flatMap(b => Iterator(((b >> 4) & 0xf).toByte, (b & 0xf).toByte)).toArray

  /** Every account-trie position on `accountHash`'s key, depth 0 to 64, leaf slot included (spec 022 T034, geth
    * `pathTrie.delete`). Deleted in one batch, they leave every node above the account unreachable-or-absent together:
    * whichever node had a child on the key is on the key too, so no present node is left over an absent child.
    */
  def accountPathPositions(accountHash: ByteString): Seq[(Option[ByteString], Array[Byte])] =
    val nibbles = accountNibbles(accountHash)
    (0 to nibbles.length).map(depth => (None, nibbles.take(depth)))

  /** Walk the account trie from `root` along `accountHash`'s key, reading the store through `read`. */
  def lookup(
      root: ByteString,
      accountHash: ByteString,
      read: (ByteString, Seq[ByteString]) => Option[MptNode]
  ): Lookup =
    val key = accountNibbles(accountHash)

    def pathsetAt(depth: Int): Seq[ByteString] =
      Seq(ByteString(HexPrefix.encode(key.take(depth), isLeaf = false)))

    @tailrec
    def at(hash: ByteString, depth: Int): Lookup =
      val path = pathsetAt(depth)
      read(hash, path) match
        case None => Lookup.Missing(path, hash)
        case Some(node) =>
          descend(node, depth) match
            case Left((childHash, childDepth)) => at(childHash, childDepth)
            case Right(result)                 => result

    // A hash reference to follow, or the end of the walk. An inline child is walked in place.
    @tailrec
    def descend(node: MptNode, depth: Int): Either[(ByteString, Int), Lookup] =
      node match
        case b: BranchNode if depth < key.length =>
          b.children(key(depth)) match
            case h: HashNode => Left((ByteString(h.hashNode), depth + 1))
            case NullNode    => Right(Lookup.Absent)
            case other       => descend(other, depth + 1)
        case e: ExtensionNode if key.slice(depth, depth + e.sharedKey.length).sameElements(e.sharedKey.toArray) =>
          e.next match
            case h: HashNode => Left((ByteString(h.hashNode), depth + e.sharedKey.length))
            case other       => descend(other, depth + e.sharedKey.length)
        case l: LeafNode if key.drop(depth).sameElements(l.key.toArray) =>
          // A leaf that is not an account stands for nothing to complete; a valid state trie has none.
          Right(Account(l.value).toOption.fold[Lookup](Lookup.Absent)(Lookup.Found(_)))
        case _ => Right(Lookup.Absent)

    at(root, 0)

  private def hex(b: ByteString): String = org.bouncycastle.util.encoders.Hex.toHexString(b.take(8).toArray)
