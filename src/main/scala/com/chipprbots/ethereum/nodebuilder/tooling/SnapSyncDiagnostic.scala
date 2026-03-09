package com.chipprbots.ethereum.nodebuilder.tooling

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.rocksdb._

import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.db.storage.NodeStorage.{NodeEncoded, NodeHash}
import com.chipprbots.ethereum.mpt._

import scala.collection.mutable

/** Standalone diagnostic tool for inspecting RocksDB state after SNAP sync.
  *
  * Usage: sbt "runMain com.chipprbots.ethereum.nodebuilder.tooling.SnapSyncDiagnostic [db-path]"
  *
  * Default db-path: /media/dev/2tb/data/blockchain/fukuii/etc/rocksdb-backup-pre-bug20
  */
object SnapSyncDiagnostic {

  def main(args: Array[String]): Unit = {
    val dbPath = args.headOption.getOrElse(
      "/media/dev/2tb/data/blockchain/fukuii/etc/rocksdb-backup-pre-bug20"
    )

    println(s"\n=== SNAP Sync RocksDB Diagnostic ===")
    println(s"Database: $dbPath\n")

    val config = new RocksDbConfig {
      val path = dbPath
      val createIfMissing = false
      val paranoidChecks = false
      val maxThreads = 1
      val maxOpenFiles = 64
      val verifyChecksums = false
      val levelCompaction = true
      val blockSize = 16384L
      val blockCacheSize = 32L * 1024 * 1024
    }

    val dataSource = RocksDbDataSource(config, Namespaces.nsSeq)

    try {
      checkAppState(dataSource)
      val stateRoot = getStateRoot(dataSource)
      stateRoot.foreach { root =>
        checkTrieDepth(dataSource, root)
      }
    } finally {
      dataSource.close()
    }

    println("\n=== Diagnostic Complete ===")
  }

  private def checkAppState(dataSource: RocksDbDataSource): Unit = {
    println("--- AppState Flags ---")
    val ns = Namespaces.AppStateNamespace

    val flags = Seq(
      "SnapSyncDone",
      "BytecodeRecoveryDone",
      "StorageRecoveryDone",
      "SnapSyncPivotBlock",
      "SnapSyncStateRoot"
    )

    flags.foreach { key =>
      val value = dataSource.getOptimized(ns, key.getBytes("UTF-8"))
      val display = value.map(v => new String(v, "UTF-8")).getOrElse("<not set>")
      println(s"  $key = $display")
    }
    println()
  }

  private def getStateRoot(dataSource: RocksDbDataSource): Option[ByteString] = {
    val ns = Namespaces.AppStateNamespace
    dataSource
      .getOptimized(ns, "SnapSyncStateRoot".getBytes("UTF-8"))
      .map(v => ByteString(Hex.decode(new String(v, "UTF-8"))))
  }

  private def checkTrieDepth(dataSource: RocksDbDataSource, stateRoot: ByteString): Unit = {
    println("--- Trie Node Inspection ---")
    val nodeNs = Namespaces.NodeNamespace

    // Check if root node exists
    val rootBytes = dataSource.getOptimized(nodeNs, stateRoot.toArray)
    rootBytes match {
      case None =>
        println(s"  ROOT NODE MISSING: ${Hex.toHexString(stateRoot.toArray.take(8))}...")
        return
      case Some(raw) =>
        println(s"  Root node: ${Hex.toHexString(stateRoot.toArray.take(8))}... (${raw.length} bytes)")

        // Try to decode — might be wrapped in StoredNode (reference counting)
        val nodeEncoded = tryUnwrapStoredNode(raw)
        println(s"  After StoredNode unwrap: ${nodeEncoded.length} bytes")

        // Decode and walk
        try {
          val rootNode = MptTraversals.decodeNode(nodeEncoded)
          println(s"  Root node type: ${rootNode.getClass.getSimpleName}")

          rootNode match {
            case branch: BranchNode =>
              println(s"  Root has ${branch.children.count(!_.isNull)} non-null children")
              walkDepth(dataSource, branch, nodeNs, maxDepth = 4, maxNodesPerLevel = 5)
            case ext: ExtensionNode =>
              println(s"  Root is ExtensionNode with key length ${ext.sharedKey.length}")
              walkDepth(dataSource, ext, nodeNs, maxDepth = 4, maxNodesPerLevel = 5)
            case _ =>
              println(s"  Unexpected root type: ${rootNode.getClass.getSimpleName}")
          }
        } catch {
          case e: Exception =>
            println(s"  ERROR decoding root: ${e.getClass.getSimpleName}: ${e.getMessage}")
        }
    }
    println()
  }

  private def walkDepth(
      dataSource: RocksDbDataSource,
      rootNode: MptNode,
      nodeNs: IndexedSeq[Byte],
      maxDepth: Int,
      maxNodesPerLevel: Int
  ): Unit = {
    // BFS with depth tracking
    case class QueueEntry(node: MptNode, depth: Int, resolvedHash: Option[Array[Byte]])
    val queue = mutable.Queue[QueueEntry]()
    val depthStats = mutable.Map[Int, (Int, Int, Int)]() // depth -> (total, found, missing)

    // Seed with root's children
    rootNode match {
      case branch: BranchNode =>
        branch.children.foreach { child =>
          if (!child.isNull) queue.enqueue(QueueEntry(child, 1, None))
        }
      case ext: ExtensionNode =>
        queue.enqueue(QueueEntry(ext.next, 1, None))
      case _ => ()
    }

    val visited = mutable.Set[ByteString]()
    var totalResolved = 0

    while (queue.nonEmpty) {
      val entry = queue.dequeue()
      if (entry.depth > maxDepth) {
        // Just count but don't recurse
        entry.node match {
          case hash: HashNode =>
            val stats = depthStats.getOrElseUpdate(entry.depth, (0, 0, 0))
            depthStats(entry.depth) = (stats._1 + 1, stats._2, stats._3)
          case _ => ()
        }
      } else {
        entry.node match {
          case hash: HashNode =>
            val hashKey = ByteString(hash.hash)
            if (!visited.contains(hashKey)) {
              visited += hashKey
              val stats = depthStats.getOrElseUpdate(entry.depth, (0, 0, 0))

              val rawOpt = dataSource.getOptimized(nodeNs, hash.hash)
              rawOpt match {
                case None =>
                  depthStats(entry.depth) = (stats._1 + 1, stats._2, stats._3 + 1)
                case Some(raw) =>
                  depthStats(entry.depth) = (stats._1 + 1, stats._2 + 1, stats._3)
                  totalResolved += 1

                  // Decode and enqueue children
                  try {
                    val nodeEncoded = tryUnwrapStoredNode(raw)
                    val decoded = MptTraversals.decodeNode(nodeEncoded)
                    decoded match {
                      case b: BranchNode =>
                        b.children.foreach { child =>
                          if (!child.isNull) queue.enqueue(QueueEntry(child, entry.depth + 1, None))
                        }
                      case e: ExtensionNode =>
                        queue.enqueue(QueueEntry(e.next, entry.depth + 1, None))
                      case _: LeafNode => () // leaf — done
                      case _ => ()
                    }
                  } catch {
                    case e: Exception =>
                      println(s"    DECODE ERROR at depth ${entry.depth}: ${e.getClass.getSimpleName}: ${e.getMessage}")
                  }
              }
            }

          case branch: BranchNode =>
            // Inline branch (not from a hash node) — just enqueue children
            branch.children.foreach { child =>
              if (!child.isNull) queue.enqueue(QueueEntry(child, entry.depth, None))
            }

          case ext: ExtensionNode =>
            queue.enqueue(QueueEntry(ext.next, entry.depth, None))

          case _: LeafNode | NullNode => ()
        }
      }
    }

    println(s"\n  Depth-by-depth trie inspection (max depth $maxDepth):")
    depthStats.toSeq.sortBy(_._1).foreach { case (depth, (total, found, missing)) =>
      val status = if (missing > 0) s" *** $missing MISSING ***" else ""
      println(f"    Depth $depth: $total%,d hash nodes, $found%,d found, $missing%,d missing$status")
    }
    println(s"  Total nodes resolved: ${totalResolved}")
  }

  /** Try to unwrap StoredNode(nodeEncoded, references, lastUsedByBlock) wrapper.
    * StoredNode is RLP-encoded as: RLPList(nodeEncoded, references, lastUsedByBlock).
    * Falls back to raw bytes if unwrapping fails.
    */
  private def tryUnwrapStoredNode(raw: Array[Byte]): Array[Byte] =
    try {
      import com.chipprbots.ethereum.rlp._
      val decoded = rawDecode(raw)
      decoded match {
        case RLPList(nodeEncoded, _, _*) =>
          // StoredNode wrapper: first element is the actual node RLP
          nodeEncoded match {
            case RLPValue(bytes) => bytes
            case list: RLPList =>
              // Node itself is an RLP list — re-encode it
              encode(list)
            case _ => raw
          }
        case _ => raw // Not a StoredNode wrapper — raw bytes ARE the node
      }
    } catch {
      case _: Exception => raw
    }

}
