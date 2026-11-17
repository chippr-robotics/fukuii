package com.chipprbots.ethereum.db.storage.pathstate

/** Which changeset records a canonical commit deletes (spec 018 data-model 4.5, 4.6).
  *
  * The two pruning modes are one write path with different retention. `basic` keeps the records of the last `retained`
  * blocks, the anchor's own among them, and deletes the rest in the batch of the block that puts them below the floor.
  * `archive` keeps every record.
  *
  * @param retained
  *   how many blocks keep their record, given the anchor's number and the finalized block's, if there is one
  *   ([[StateConfig.retained]])
  */
final class RetentionPolicy(mode: StateConfig.Mode, retained: (BigInt, Option[BigInt]) => Int):

  /** The lowest height that keeps its record once block `number` is the anchor, or `None` when every record is kept.
    * Never above `number`: the block being committed keeps its own.
    */
  def floor(number: BigInt, finalized: Option[BigInt]): Option[BigInt] = mode match
    case StateConfig.Mode.Archive => None
    case StateConfig.Mode.Basic   => Some((number - retained(number, finalized) + 1).max(0).min(number))

  /** How many heights one batch prunes when the floor is `behind` heights above what has been pruned. Following the
    * head it is one or two; after a floor that jumped (a node switched from `archive`, a state synced again far above
    * the last one) it catches up in larger steps, each still one read for a height that has no record.
    */
  def heightsPerBatch(behind: BigInt): Int =
    if behind <= RetentionPolicy.FollowingHeights then RetentionPolicy.FollowingHeights
    else behind.min(RetentionPolicy.CatchUpHeights).toInt

object RetentionPolicy:
  val FollowingHeights: Int = ChangesetStorage.DefaultPruneHeights
  val CatchUpHeights: Int = 1024

  def apply(config: StateConfig): RetentionPolicy = new RetentionPolicy(config.pruning.mode, config.retained)

  /** Every record is kept: `archive`, and what a test that is not about retention runs on. */
  val KeepAll: RetentionPolicy = new RetentionPolicy(StateConfig.Mode.Archive, (_, _) => 0)
