package com.chipprbots.ethereum.console.ops

/** `dbcheck` and `compact` (spec 029 FR-054, contracts/actions.md): the database consistency check of the node's start,
  * run now, reporting what it finds and stopping nothing. On a node where that check is not meaningful (one that synced
  * by SNAP or takes its blocks from the Engine API has no headers below its pivot, by design) it is not run, and the
  * dialog says why: it would report faults that are not faults (vault, slice 7).
  */
object DatabaseActions:

  /** What the check found: the points it read, and the ones with no hash for their number or no header for their hash,
    * the first of them named.
    */
  final case class Result(best: BigInt, step: Int, checked: Long, missing: Long, first: Seq[String], cancelled: Boolean)

  /** What the action needs of the node.
    *
    * @param notMeaningful
    *   why the check says nothing on this node, if it does not
    * @param best
    *   the best block's number
    * @param step
    *   it reads every `step`-th block
    * @param scan
    *   runs the check, telling how far it is (points read, points to read)
    */
  final case class Node(
      notMeaningful: () => Option[String],
      best: () => BigInt,
      step: Int,
      scan: ((Long, Long) => Unit) => Result
  )

  /** One store compacted: its SST bytes before and after, where they could be read. */
  final case class Compacted(store: String, before: Option[Long], after: Option[Long])

  /** How a whole-database compaction ended.
    *
    * @param notReached
    *   the stores a cancellation (the node stopping) left uncompacted
    */
  enum CompactResult:
    case AlreadyRunning
    case Finished(compacted: Vector[Compacted], notReached: Vector[String], cancelled: Boolean)

  /** What `compact` needs of the node.
    *
    * @param sstBytes
    *   the database's SST bytes now, if they can be read
    * @param stores
    *   how many stores it compacts
    * @param compact
    *   runs the compaction, largest store first, telling each store as it starts (name, place from 1, how many)
    */
  final case class CompactNode(
      sstBytes: () => Option[Long],
      stores: Int,
      compact: ((String, Int, Int) => Unit) => CompactResult
  )

  private def bytes(n: Long): String = com.chipprbots.ethereum.console.TuiText.formatBytes(n)

  def compactHandlers(node: CompactNode): Map[String, ActionHandler] = Map(
    Actions.Compact.name -> ActionHandler(
      _ =>
        Preview(
          Vector(
            s"Rewrites the files of all ${node.stores} stores, the largest first, one store at a time.",
            "It reads and writes a great deal: block import, sync and RPC slow while it runs, and on a large database " +
              "it takes hours.",
            "It needs free disk for the files it writes before the old ones go: up to the size of the store it is on.",
            "Block import and sync go on beside it; no lock is held across it.",
            "It stops by itself, between stores or inside one, when the node stops. Only one runs at a time."
          ),
          figures = Vector(
            node
              .sstBytes()
              .fold("The database's size cannot be read now.")(b => s"The database's files hold ${bytes(b)} now.")
          )
        ),
      (_, progress) =>
        node.compact((store, at, of) => progress(s"compacting store $store ($at of $of)")) match
          case CompactResult.AlreadyRunning => Left("already running: a compaction started earlier has not ended")
          case CompactResult.Finished(compacted, notReached, cancelled) =>
            val (before, after) = (compacted.flatMap(_.before).sum, compacted.flatMap(_.after).sum)
            val said =
              s"compacted ${compacted.size} of ${node.stores} stores (${bytes(before)} of files before, " +
                s"${bytes(after)} after)"
            if cancelled then
              Left(s"cancelled because the node is stopping: $said; not reached: ${notReached.mkString(", ")}")
            else Right(said)
    )
  )

  def handlers(node: Node): Map[String, ActionHandler] = Map(
    Actions.DbCheck.name -> ActionHandler(
      _ =>
        node.notMeaningful() match
          case Some(why) => Preview(Vector.empty, Some(s"not meaningful here: $why"))
          case None =>
            val best = node.best()
            val points = best / node.step.max(1) + 1
            Preview(
              Vector(
                s"Reads the number-to-hash entry and the header of every ${node.step}th block up to the best block.",
                "It is the check the node runs when it starts.",
                "It stops nothing and changes nothing: on finding a fault it reports it, here and in the log.",
                "It reads beside block import, and takes seconds to tens of seconds."
              ),
              figures = Vector(s"The best block is $best now: about ${points * 2} entries.")
            )
      ,
      (_, progress) =>
        node.notMeaningful() match
          case Some(why) => Left(s"not meaningful here: $why")
          case None =>
            val result = node.scan((done, of) => progress(s"read $done of $of sample points"))
            val said =
              s"checked ${result.checked} sample points (every ${result.step}th block up to ${result.best}): " +
                s"${result.missing} missing"
            val first = if result.missing == 0 then "" else s"; the first: ${result.first.take(5).mkString(", ")}"
            // A check that stopped early read only part of the database: its count is not the whole check's.
            if result.cancelled then Left(s"cancelled before it had read every point: $said so far$first")
            else if result.missing == 0 then Right(said)
            else Left(s"$said$first")
    )
  )
