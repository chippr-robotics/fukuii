package com.chipprbots.ethereum.console

import java.time.Instant

import com.chipprbots.ethereum.MilestoneLog
import com.chipprbots.ethereum.console.TuiText.formatCount
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** The consensus family the header names: proof of work (ETC) or proof of stake (ETH). */
enum ChainFamily(val label: String):
  case PoW extends ChainFamily("PoW")
  case PoS extends ChainFamily("PoS")

/** What a network is for, as far as its chain id says: a public main network, a public test network, or neither that
  * the node knows of (a private or a new network).
  */
enum NetworkKind:
  case Main, Test, Other

/** How a chain's forks activate: by block number (ETC) or by timestamp (ETH). */
enum ForkSchedule:
  case ByBlock, ByTimestamp

/** A scheduled fork: its name, and the block number or Unix timestamp it activates at. */
final case class ForkPoint(name: String, at: Long)

/** The chain's identity and fork timeline as the TUI shows them (spec 027 FR-017, research §6, C-4, C-5).
  *
  * Read from the node's fork configuration for display only: nothing here changes a fork's configuration, or how
  * `forBlock` and `forTimestamp` dispatch.
  *
  * @param forks
  *   the scheduled forks, by activation (ties keep the configuration's order)
  * @param notScheduled
  *   forks the configuration names but has not scheduled: on ETC, those at `Long.MaxValue` or the far-future
  *   placeholder ([[ChainFacts.NotScheduledFrom]]) after the last scheduled one (such forks before it are other chains'
  *   forks, and are left out); on ETH, those with no timestamp
  * @param version
  *   the node's version, for the L-tier header
  */
final case class ChainFacts(
    chainId: BigInt,
    family: ChainFamily,
    schedule: ForkSchedule,
    forks: Seq[ForkPoint],
    notScheduled: Seq[String],
    version: String = ""
):
  /** Main network, test network, or neither known, by the chain id (spec 029 FR-061). */
  def kind: NetworkKind =
    if ChainFacts.MainNetworks.contains(chainId) then NetworkKind.Main
    else if ChainFacts.TestNetworks.contains(chainId) then NetworkKind.Test
    else NetworkKind.Other

  /** The latest fork active at `position` (a block number on ETC, the head's timestamp on ETH). */
  def last(position: Long): Option[ForkPoint] = forks.filter(_.at <= position).lastOption

  /** The first fork not yet active at `position`. */
  def next(position: Long): Option[ForkPoint] = forks.find(_.at > position)

  /** When `fork` activates: its block number, or its UTC time. */
  def when(fork: ForkPoint): String = schedule match
    case ForkSchedule.ByBlock     => formatNumber(fork.at)
    case ForkSchedule.ByTimestamp => ChainFacts.utc(fork.at)

  /** How far `fork` is from `position`: `4.0M away` (blocks), or `in 3d 4h`. */
  def distance(fork: ForkPoint, position: Long): String =
    val gap = (fork.at - position).max(0L)
    schedule match
      case ForkSchedule.ByBlock     => s"${formatCount(gap)} away"
      case ForkSchedule.ByTimestamp => s"in ${formatShort(gap)}"

  /** What the timeline is ordered by, for a panel title. */
  def scheduleLabel: String = schedule match
    case ForkSchedule.ByBlock     => "block"
    case ForkSchedule.ByTimestamp => "timestamp"

object ChainFacts:

  /** The chain ids of the public main networks: Ethereum and Ethereum Classic. */
  val MainNetworks: Set[BigInt] = Set(BigInt(1), BigInt(61))

  /** The chain ids of the public test networks the node ships a configuration for, or is likely to be pointed at:
    * Mordor, Sepolia, Holesky, Hoodi and Platåberget.
    */
  val TestNetworks: Set[BigInt] =
    Set(BigInt(63), BigInt(11155111L), BigInt(17000), BigInt(560048), BigInt(7091047534L))

  /** The facts of a chain's configuration. The family comes from `networkType` (C-4), not from whether timestamp forks
    * are configured.
    */
  def from(config: BlockchainConfig, version: String = ""): ChainFacts =
    config.networkType match
      case NetworkType.ETC => byBlock(config.chainId.value, config.forkBlockNumbers, version)
      case NetworkType.ETH => byTimestamp(config.chainId.value, config.forkTimestamps, version)

  /** The block number from which a fork counts as not scheduled: `Long.MaxValue`, and the far-future placeholder the
    * shipped chain configurations give a fork that has no block yet (`olympia-block-number = "1000000000000000000"`,
    * `conf/base/chains/etc-chain.conf`). No chain reaches either.
    */
  val NotScheduledFrom: BigInt = BigInt("1000000000000000000")

  /** ETC's timeline: `MilestoneLog`'s names, the scheduled ones by block number. */
  def byBlock(chainId: BigInt, forks: ForkBlockNumbers, version: String = ""): ChainFacts =
    val named = MilestoneLog.namedMilestones(forks)
    def scheduled(block: BigInt) = block < NotScheduledFrom
    val points = named.collect { case (name, block) if scheduled(block) => ForkPoint(name, block.toLong) }
    val lastScheduled = named.lastIndexWhere((_, block) => scheduled(block))
    val unscheduled = named.drop(lastScheduled + 1).map(_._1)
    ChainFacts(chainId, ChainFamily.PoW, ForkSchedule.ByBlock, points.sortBy(_.at), unscheduled, version)

  /** ETH's timeline: the timestamp forks, by activation time (C-5), Amsterdam and the BPO forks included. */
  def byTimestamp(chainId: BigInt, forks: ForkTimestamps, version: String = ""): ChainFacts =
    val named = Seq(
      "Shanghai" -> forks.shanghaiTimestamp,
      "Cancun" -> forks.cancunTimestamp,
      "Prague" -> forks.pragueTimestamp,
      "Osaka" -> forks.osakaTimestamp,
      "Amsterdam" -> forks.amsterdamTimestamp,
      "BPO1" -> forks.bpo1Timestamp,
      "BPO2" -> forks.bpo2Timestamp
    )
    val scheduled = named.collect { case (name, Some(at)) => ForkPoint(name, at) }.sortBy(_.at)
    ChainFacts(
      chainId,
      ChainFamily.PoS,
      ForkSchedule.ByTimestamp,
      scheduled,
      named.collect { case (n, None) => n },
      version
    )

  /** A Unix timestamp in UTC, ISO-8601: `2026-10-06T13:53:36Z`. */
  def utc(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString
