package com.chipprbots.ethereum.forkid

import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import cats.Monad
import cats.data.EitherT.*
import cats.implicits.*

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils.*

enum ForkIdValidationResult:
  case Connect, ErrRemoteStale, ErrLocalIncompatibleOrStale

import cats.effect.*

object ForkIdValidator:

  import ForkIdValidationResult.*

  implicit val ioLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  implicit val syncIoLogger: SelfAwareStructuredLogger[SyncIO] = Slf4jLogger.getLogger[SyncIO]

  val maxUInt64: BigInt = (BigInt(0x7fffffffffffffffL) << 1) + 1 // scalastyle:ignore magic.number

  /** go-ethereum `core/forkid/forkid.go`'s `timestampThreshold`: the ETH mainnet genesis timestamp. Used only as a
    * heuristic split point in `checkMatchingHashes` rule 1a, to recognise a remote-announced FORK_NEXT as a timestamp
    * even when our own next-unpassed fork is still in the block domain (so `effectiveHead` alone is a block number). No
    * real block number is ever this large, so this can never misfire on a pure block-fork chain (ETC).
    */
  private val timestampThreshold: BigInt = BigInt(1438269973L)

  /** Tells whether it makes sense to connect to a peer or gives a reason why it isn't a good idea.
    *
    * Mirrors go-ethereum's `newFilter` (`core/forkid/forkid.go`): block-number forks are compared against
    * `currentHeight`, timestamp forks (EIP-6122) against `currentTimestamp` — never the other way around. Before this
    * threaded a real timestamp through, every timestamp fork was compared against `currentHeight` alone; since block
    * heights and unix timestamps live in disjoint numeric ranges (a Sepolia block number is ~9,000,000, a Sepolia
    * timestamp fork is ~1,790,000,000), `currentHeight < fork` was true forever, so no timestamp fork ever counted as
    * passed and a stale (pre-upgrade) peer was accepted as compatible. See WI-13 / issue #1429.
    *
    * @param genesisHash
    *   \- hash of the genesis block of the current chain
    * @param genesisTimestamp
    *   \- unix timestamp of the genesis block; forks at or before it are dropped per EIP-6122 (see
    *   [[ForkId.gatherTimestampForks]])
    * @param config
    *   \- local client's blockchain configuration
    * @param currentHeight
    *   \- number of the block at the current tip
    * @param currentTimestamp
    *   \- unix timestamp of the block at the current tip, as a uint64 (see [[com.chipprbots.ethereum.domain.Timestamp]]
    *   for why this is not a plain signed comparison)
    * @param remoteForkId
    *   \- ForkId announced by the connecting peer
    * @return
    *   One of:
    *   - [[com.chipprbots.ethereum.forkid.ForkIdValidationResult.Connect]] - It is safe to connect to the peer
    *   - [[com.chipprbots.ethereum.forkid.ForkIdValidationResult.ErrRemoteStale]] - Remote is stale, don't connect
    *   - [[com.chipprbots.ethereum.forkid.ForkIdValidationResult.ErrLocalIncompatibleOrStale]] - Local is incompatible
    *     or stale, don't connect
    */
  def validatePeer[F[_]: Monad: Logger](
      genesisHash: ByteString,
      genesisTimestamp: Long,
      config: BlockchainConfig
  )(currentHeight: BigInt, currentTimestamp: Long, remoteForkId: ForkId): F[ForkIdValidationResult] =
    // Same (block-forks-first, then timestamp-forks) shape as ForkId.create's `allForks` — the two MUST agree,
    // or a peer validates its own announced fork id differently than we'd have computed it ourselves.
    val taggedForks =
      ForkId.gatherBlockForks(config).map((_, false)) ++
        ForkId.gatherTimestampForks(config, genesisTimestamp).map((_, true))
    // Sign-extension guard: see ForkId.create's identical comment. A currentTimestamp at or above 2^63 would
    // otherwise read as negative and no timestamp fork would ever count as passed.
    val currentTimestampUnsigned = Timestamp(currentTimestamp).toUnsignedBigInt
    validatePeer[F](genesisHash, taggedForks)(currentHeight, currentTimestampUnsigned, remoteForkId)

  /** Pure-block-domain core, preserved for callers with a single homogeneous block-number fork list and no timestamp
    * axis at all — ETC/Mordor (`gatherTimestampForks` is always empty there) and the direct ETH-pre-merge assertions in
    * [[ForkIdValidatorSpec]]. `currentTimestamp` is fixed at 0: the only place it is read is the OR-clause in
    * `checkMatchingHashes`, which is unreachable unless a fork value exceeds `timestampThreshold` (~1.4 billion) — no
    * real block number does.
    */
  private[forkid] def validatePeer[F[_]: Monad: Logger](
      genesisHash: ByteString,
      forks: List[BigInt]
  )(currentHeight: BigInt, remoteId: ForkId): F[ForkIdValidationResult] =
    validatePeer[F](genesisHash, forks.map((_, false)))(currentHeight, BigInt(0), remoteId)

  /** The domain-aware core every other overload delegates to. `forks` is `(value, isTimestamp)` pairs in go-ethereum's
    * `forksByBlock ++ forksByTime` order — block forks first, then timestamp forks, NOT numerically merged. (Numeric
    * merge is wrong even though it happens to coincide for realistic chains, because current block heights are far
    * smaller than unix timestamps: it's the wrong axis, not a coincidentally-right one.)
    */
  private[forkid] def validatePeer[F[_]: Monad: Logger](
      genesisHash: ByteString,
      forks: List[(BigInt, Boolean)]
  )(currentHeight: BigInt, currentTimestamp: BigInt, remoteId: ForkId): F[ForkIdValidationResult] =
    val checksums: Vector[BigInt] = calculateChecksums(genesisHash, forks.map(_._1))
    val hasTimestampForks = forks.exists(_._2)

    def headFor(isTimestamp: Boolean): BigInt = if isTimestamp then currentTimestamp else currentHeight

    // Find the first unpassed fork, its axis and its index — mirrors go-ethereum's `if head >= fork { continue }`
    // loop, picking the axis-correct head per entry instead of one head for every entry.
    val (unpassedForkIsTimestamp, unpassedForkIndex) =
      forks.zipWithIndex
        .find { case ((fork, isTimestamp), _) => headFor(isTimestamp) < fork }
        .map { case ((_, isTimestamp), idx) => (isTimestamp, idx) }
        // All forks passed. go-ethereum's sentinel (`forks = append(forks, math.MaxUint64)`) lands in the
        // timestamp domain unless the chain has no timestamp forks at all, in which case it stays in the block
        // domain (`newFilter`'s `if len(forksByTime) == 0` bump) — `hasTimestampForks` reproduces that split.
        .getOrElse((hasTimestampForks, forks.length))

    val effectiveHead = headFor(unpassedForkIsTimestamp)

    // The checks are left biased -> whenever a result is found we need to short circuit
    val validate = (for
      _ <- liftF(Logger[F].trace(s"Before checkMatchingHashes"))
      matching <- fromEither[F](
        checkMatchingHashes(checksums(unpassedForkIndex), remoteId, effectiveHead, currentTimestamp).toLeft(
          "hashes didn't match"
        )
      )
      _ <- liftF(Logger[F].trace(s"checkMatchingHashes result: $matching"))
      _ <- liftF(Logger[F].trace(s"Before checkSubset"))
      sub <- fromEither[F](
        checkSubset(checksums, forks.map(_._1), remoteId, unpassedForkIndex).toLeft("not in subset")
      )
      _ <- liftF(Logger[F].trace(s"checkSubset result: $sub"))
      _ <- liftF(Logger[F].trace(s"Before checkSuperset"))
      sup <- fromEither[F](checkSuperset(checksums, remoteId, unpassedForkIndex).toLeft("not in superset"))
      _ <- liftF(Logger[F].trace(s"checkSuperset result: $sup"))
      _ <- liftF(Logger[F].trace(s"No check succeeded"))
      _ <- fromEither[F](Either.left[ForkIdValidationResult, Unit](ErrLocalIncompatibleOrStale))
    yield ()).value

    for
      _ <- Logger[F].debug(s"FORKID_VALIDATION: Validating remote $remoteId against local state")
      _ <- Logger[F].debug(
        s"FORKID_VALIDATION: Local height: $currentHeight, timestamp: $currentTimestamp, " +
          s"unpassed fork index: $unpassedForkIndex (isTimestamp=$unpassedForkIsTimestamp)"
      )
      _ <- Logger[F].debug(
        s"FORKID_VALIDATION: Local expected checksum: 0x${checksums(unpassedForkIndex).toString(16)}, remote hash: $remoteId"
      )
      _ <- Logger[F].trace(s"FORKID_VALIDATION: Fork list: $forks")
      _ <- Logger[F].trace(s"FORKID_VALIDATION: Checksum list: $checksums")
      res <- validate.map(_.swap)
      _ <- Logger[F].info(s"FORKID_VALIDATION: Validation result: $res for remote $remoteId")
    yield res.getOrElse(Connect)

  private def calculateChecksums(
      genesisHash: ByteString,
      forks: List[BigInt]
  ): Vector[BigInt] =
    val crc = new CRC32()
    crc.update(genesisHash.asByteBuffer)
    val genesisChecksum = BigInt(crc.getValue())

    genesisChecksum +: forks.map { fork =>
      crc.update(bigIntToBytes(fork, 8))
      BigInt(crc.getValue())
    }.toVector

  /** 1) If local and remote FORK_HASH matches, compare local head to FORK_NEXT. The two nodes are in the same fork
    * state currently. They might know of differing future forks, but that’s not relevant until the fork triggers (might
    * be postponed, nodes might be updated to match). 1a) A remotely announced but remotely not passed block is already
    * passed locally, disconnect, since the chains are incompatible. 1b) No remotely announced fork; or not yet passed
    * locally, connect.
    *
    * `effectiveHead` is the axis-correct head (block or timestamp) for OUR next-unpassed fork. The second disjunct in
    * 1a mirrors go-ethereum's `id.Next > timestampThreshold && time >= id.Next`: it catches a remote `next` that is
    * itself a timestamp — and already passed by our real clock — even while `effectiveHead` is still a block number
    * (i.e. our own next-unpassed fork hasn't crossed into the timestamp domain yet).
    */
  private def checkMatchingHashes(
      checksum: BigInt,
      remoteId: ForkId,
      effectiveHead: BigInt,
      currentTimestamp: BigInt
  ): Option[ForkIdValidationResult] =
    remoteId match
      case ForkId(hash, _) if checksum != hash => None
      case ForkId(_, Some(next)) if effectiveHead >= next || (next > timestampThreshold && currentTimestamp >= next) =>
        Some(ErrLocalIncompatibleOrStale)
      case _ => Some(Connect)

  /** 2) If the remote FORK_HASH is a subset of the local past forks and the remote FORK_NEXT matches with the locally
    * following fork block number, connect. Remote node is currently syncing. It might eventually diverge from us, but
    * at this current point in time we don’t have enough information.
    */
  def checkSubset(
      checksums: Vector[BigInt],
      forks: List[BigInt],
      remoteId: ForkId,
      i: Int
  ): Option[ForkIdValidationResult] =
    checksums
      .zip(forks)
      .take(i)
      .collectFirst {
        case (sum, fork) if sum == remoteId.hash =>
          if fork == remoteId.next.getOrElse(0) then Connect else ErrRemoteStale
      }

  /** 3) If the remote FORK_HASH is a superset of the local past forks and can be completed with locally known future
    * forks, connect. Local node is currently syncing. It might eventually diverge from the remote, but at this current
    * point in time we don’t have enough information.
    */
  def checkSuperset(checksums: Vector[BigInt], remoteId: ForkId, i: Int): Option[ForkIdValidationResult] =
    checksums.drop(i).collectFirst { case sum if sum == remoteId.hash => Connect }
