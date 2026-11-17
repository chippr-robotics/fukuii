package com.chipprbots.ethereum.console.ops

/** `dbcheck` (spec 029 FR-054, contracts/actions.md): the database consistency check of the node's start, run now,
  * reporting what it finds and stopping nothing. On a node where that check is not meaningful (one that synced by SNAP
  * or takes its blocks from the Engine API has no headers below its pivot, by design) it is not run, and the dialog
  * says why: it would report faults that are not faults (vault, slice 7).
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
