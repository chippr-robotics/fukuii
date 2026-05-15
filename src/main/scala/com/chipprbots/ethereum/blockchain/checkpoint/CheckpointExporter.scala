package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NullNode

/** Produces a `.checkpoint` archive for a specified block by walking the state trie. The resulting file is consumable by
  * [[CheckpointImporter]].
  *
  * Algorithm:
  *
  *   1. Resolve the block header + chain weight from `BlockchainReader`. Refuse if either is missing.
  *   1. DFS the account trie from `header.stateRoot`. Emit every raw node we visit. For each leaf, decode the account
  *      and remember its `storageRoot` and `codeHash`.
  *   1. DFS each unique non-empty `storageRoot`. Emit every raw node we visit.
  *   1. Emit each unique non-empty `codeHash` looked up from `evmCodeStorage`.
  *   1. Close the archive with the CRC32 trailer.
  *
  * Read-only — does not mutate any storage. The streaming design keeps RAM bounded to ~O(unique hashes seen), not O(trie
  * size).
  */
final class CheckpointExporter(
    nodeStorage: NodeStorage,
    evmCodeStorage: EvmCodeStorage,
    blockchainReader: BlockchainReader,
    chainId: BigInt
) {
  import CheckpointExporter._
  private val log = LoggerFactory.getLogger(getClass)

  def exportArchive(blockNumber: BigInt, output: Path, gzip: Boolean = false): Either[ExportError, ExportResult] = {
    val header = blockchainReader.getBlockHeaderByNumber(blockNumber) match {
      case Some(h) => h
      case None    => return Left(NoSuchBlock(blockNumber))
    }
    val weight = blockchainReader.getChainWeightByHash(header.hash) match {
      case Some(w) => w
      case None    => return Left(NoChainWeight(blockNumber))
    }

    val start = System.currentTimeMillis()
    val raw = new FileOutputStream(output.toFile)
    val out =
      if (gzip) new GZIPOutputStream(new BufferedOutputStream(raw, 65536), 65536)
      else new BufferedOutputStream(raw, 65536)
    val writer = new CheckpointArchive.Writer(out)
    val visited = new java.util.HashSet[ByteString]
    val codeHashes = new java.util.HashSet[ByteString]
    val storageRoots = new scala.collection.mutable.Queue[ByteString]
    var nodesEmitted = 0L
    var bytecodesEmitted = 0L

    try {
      writer.writeHeader(
        CheckpointArchive.Header(
          chainId = chainId.toLong,
          blockHeader = header,
          chainWeight = weight
        )
      )

      log.info(
        "[CHECKPOINT EXPORT] block={} stateRoot={} writing to {}",
        blockNumber,
        hex8(header.stateRoot),
        output
      )

      // Phase 1: account trie
      walkTrie(
        rootHash = header.stateRoot,
        isMainTrie = true,
        writer = writer,
        visited = visited,
        codeHashes = codeHashes,
        storageRoots = storageRoots,
        onNodeEmitted = () => {
          nodesEmitted += 1
          if (nodesEmitted % LogInterval == 0)
            log.info(
              "[CHECKPOINT EXPORT] nodes={} bytecodes={} storageQueue={}",
              nodesEmitted,
              bytecodesEmitted,
              storageRoots.size
            )
        }
      ) match {
        case Right(_)  => ()
        case Left(err) => return Left(err)
      }

      // Phase 2: per-account storage tries
      while (storageRoots.nonEmpty) {
        val sroot = storageRoots.dequeue()
        if (sroot != Account.EmptyStorageRootHash) {
          walkTrie(
            rootHash = sroot,
            isMainTrie = false,
            writer = writer,
            visited = visited,
            codeHashes = codeHashes,
            storageRoots = storageRoots,
            onNodeEmitted = () => {
              nodesEmitted += 1
              if (nodesEmitted % LogInterval == 0)
                log.info(
                  "[CHECKPOINT EXPORT] nodes={} bytecodes={} storageQueue={}",
                  nodesEmitted,
                  bytecodesEmitted,
                  storageRoots.size
                )
            }
          ) match {
            case Right(_)  => ()
            case Left(err) => return Left(err)
          }
        }
      }

      // Phase 3: bytecodes
      val it = codeHashes.iterator()
      while (it.hasNext) {
        val ch = it.next()
        if (ch != Account.EmptyCodeHash) {
          evmCodeStorage.get(ch) match {
            case Some(code) =>
              writer.writeBytecode(ch, code.toArray)
              bytecodesEmitted += 1
            case None =>
              return Left(MissingBytecode(ch))
          }
        }
      }

      writer.finish()
    } finally
      try out.close()
      catch { case _: Throwable => () }

    val elapsed = System.currentTimeMillis() - start
    log.info(
      "[CHECKPOINT EXPORT] complete: block={} nodes={} bytecodes={} elapsed={}s output={}",
      blockNumber,
      nodesEmitted,
      bytecodesEmitted,
      elapsed / 1000,
      output
    )
    Right(ExportResult(blockNumber, nodesEmitted, bytecodesEmitted, elapsed))
  }

  private def walkTrie(
      rootHash: ByteString,
      isMainTrie: Boolean,
      writer: CheckpointArchive.Writer,
      visited: java.util.HashSet[ByteString],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[ByteString],
      onNodeEmitted: () => Unit
  ): Either[ExportError, Unit] = {
    val stack = new scala.collection.mutable.Stack[ByteString]
    stack.push(rootHash)
    while (stack.nonEmpty) {
      val h = stack.pop()
      if (visited.add(h)) {
        nodeStorage.get(h) match {
          case None      => return Left(MissingTrieNode(h, isMainTrie))
          case Some(rlp) =>
            writer.writeNode(h, rlp)
            onNodeEmitted()
            val decoded = MptTraversals.decodeNode(rlp)
            collectChildren(decoded, isMainTrie, stack, codeHashes, storageRoots)
        }
      }
    }
    Right(())
  }

  /** Walk a decoded node's tree pulling out: hash references to push onto the work stack; account info from leaves of
    * the main trie.
    */
  private def collectChildren(
      node: MptNode,
      isMainTrie: Boolean,
      stack: scala.collection.mutable.Stack[ByteString],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[ByteString]
  ): Unit = node match {
    case LeafNode(_, value, _, _, _) if isMainTrie =>
      Account(value) match {
        case scala.util.Success(acct) =>
          if (acct.storageRoot != Account.EmptyStorageRootHash) storageRoots += acct.storageRoot
          if (acct.codeHash != Account.EmptyCodeHash) codeHashes.add(acct.codeHash)
        case scala.util.Failure(_) =>
        // Storage-only or malformed leaf — best-effort; bytecodes still resolved per-trie.
      }
    case _: LeafNode =>
    // Storage slot leaf; no further traversal
    case ExtensionNode(_, next, _, _, _) =>
      collectChildren(next, isMainTrie, stack, codeHashes, storageRoots)
    case BranchNode(children, _, _, _, _) =>
      var i = 0
      while (i < children.length) {
        collectChildren(children(i), isMainTrie, stack, codeHashes, storageRoots)
        i += 1
      }
    case HashNode(hash) =>
      stack.push(ByteString(hash))
    case NullNode => ()
  }

  private def hex8(bs: ByteString): String =
    bs.take(8).toArray.map("%02x".format(_)).mkString
}

object CheckpointExporter {
  val LogInterval: Long = 100000L

  sealed trait ExportError extends Product with Serializable
  final case class NoSuchBlock(blockNumber: BigInt) extends ExportError
  final case class NoChainWeight(blockNumber: BigInt) extends ExportError
  final case class MissingTrieNode(hash: ByteString, isMainTrie: Boolean) extends ExportError
  final case class MissingBytecode(hash: ByteString) extends ExportError

  final case class ExportResult(
      blockNumber: BigInt,
      nodesExported: Long,
      bytecodesExported: Long,
      elapsedMs: Long
  )
}
