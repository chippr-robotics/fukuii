package com.chipprbots.ethereum.console.ops

/** The mining actions of an ETC node (spec 029 FR-054, contracts/actions.md), by what the node's own functions really
  * do (forge, slice 7): the built-in miner is started by the node's start and by nothing else, so `mining start` is
  * always refused, with the reason; a miner stopped here cannot be started again while the node runs, so `mining stop`
  * is a Halt and says so; and a new coinbase pays from the next block template, not for work already handed out.
  *
  * On a node that takes its blocks from a consensus client none of these is offered: the node builds no handler for
  * them, and says why.
  */
object MiningActions:

  /** What the actions need of the node.
    *
    * @param enabledAtStart
    *   the node was started with mining on: it has a built-in miner
    * @param running
    *   the built-in miner is mining now
    * @param stop
    *   what `miner_stop` runs
    * @param coinbase
    *   the address mined blocks pay now, as `0x` and 40 digits
    * @param setCoinbase
    *   what `miner_setEtherbase` runs
    */
  final case class Node(
      enabledAtStart: Boolean,
      running: () => Boolean,
      stop: () => Either[String, Unit],
      coinbase: () => String,
      setCoinbase: String => Either[String, Unit]
  )

  /** Why the node offers none of these when it takes its blocks from a consensus client. */
  val NotProofOfWork: String =
    "this is a proof-of-stake node: blocks come from the consensus client, and the fee recipient is set there"

  val NoMinerToStart: String =
    "this node was started with mining off: there is no miner to start; " +
      "set fukuii.mining.mining-enabled = true and restart"

  val CannotStartAgain: String =
    "the built-in miner is started when the node starts; after a stop, fukuii cannot start it again: restart the node"

  val AlreadyStopped: String = "the built-in miner is already stopped; it starts again only when the node restarts"

  val AlreadyMining: String = "the built-in miner is mining: there is nothing to start"

  private val ZeroAddress = "0x" + "0" * 40

  private def refused(why: String): Preview = Preview(Vector.empty, Some(why))

  val NoMinerToStop: String = "this node was started with mining off: there is no miner to stop"

  /** Why the built-in miner cannot be started from here, in the state the node is in. */
  private def cannotStart(node: Node): String =
    if !node.enabledAtStart then NoMinerToStart else if node.running() then AlreadyMining else CannotStartAgain

  def handlers(node: Node): Map[String, ActionHandler] = Map(
    // Never runs: there is no state of this node in which the built-in miner can be started from here. The dialog
    // says which of the three reasons holds.
    Actions.MiningStart.name -> ActionHandler(
      _ => refused(cannotStart(node)),
      (_, _) => Left(cannotStart(node))
    ),
    Actions.MiningStop.name -> ActionHandler(
      _ =>
        if !node.enabledAtStart then refused(NoMinerToStop)
        else if !node.running() then refused(AlreadyStopped)
        else
          Preview(
            Vector(
              "Stops this node's built-in miner until the node restarts: fukuii cannot start it again while it runs.",
              "A search already running finishes its current round, and a block it finds is still imported.",
              "External miners using eth_getWork are not affected.",
              s"Mining rewards stop going to ${node.coinbase()} from this node's own miner."
            )
          )
      ,
      (_, _) =>
        if !node.enabledAtStart then Left(NoMinerToStop)
        else if !node.running() then Left(AlreadyStopped)
        else node.stop().map(_ => "the built-in miner is stopped until the node restarts")
    ),
    Actions.Coinbase.name -> ActionHandler(
      request =>
        val to = request.param("address").getOrElse("")
        val now = node.coinbase()
        if !to.matches("^0x[0-9a-fA-F]{40}$") then refused("a coinbase must be 0x followed by exactly 40 hex digits")
        else if to.equalsIgnoreCase(now) then refused(s"the coinbase is already $now")
        else
          Preview(
            Vector(
              s"Mining rewards for blocks mined from the next block template on go to $to.",
              s"Work already handed out, and the search now running, still pays $now.",
              "This change is not saved: on restart the coinbase returns to fukuii.mining.coinbase."
            ) ++ Option.when(to.equalsIgnoreCase(ZeroAddress))(
              s"$ZeroAddress has no owner: rewards sent there are lost permanently."
            )
          )
      ,
      (request, _) =>
        val to = request.param("address").getOrElse("")
        if !to.matches("^0x[0-9a-fA-F]{40}$") then Left("a coinbase must be 0x followed by exactly 40 hex digits")
        else node.setCoinbase(to).map(_ => s"blocks mined from the next template on pay $to")
    )
  )
