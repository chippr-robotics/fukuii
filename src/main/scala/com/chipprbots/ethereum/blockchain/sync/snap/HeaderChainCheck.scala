package com.chipprbots.ethereum.blockchain.sync.snap

import java.security.SecureRandom

import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.utils.BlockchainConfig

/** How the SNAP header download ([[ChainDownloader]]) checks the headers it stores, chosen once per chain when SNAP
  * starts ([[HeaderChainCheck.forChain]]).
  *
  *   - [[HeaderChainCheck.LinkOnly]]: each header must name the previous one as its parent, and nothing else is
  *     checked. A proof-of-work chain whose import validator does not separate the seal from the other rules (test
  *     mode).
  *   - [[HeaderChainCheck.Validated]]: proof-of-work chains (ETC, Mordor). Every header is also validated against its
  *     parent by the block-import header validator, and the stored chain is what SNAP finalisation anchors the pivot to
  *     ([[PivotAnchor]]).
  *   - [[HeaderChainCheck.ProofOfStake]]: chains with a terminal total difficulty (ETH, Sepolia, and the chains that
  *     were proof of stake from genesis). Headers before the merge are checked for their place in the chain, headers
  *     after it by the post-merge header rules, and the transition between them by the terminal total difficulty. The
  *     SNAP pivot comes from the consensus layer, and finalisation requires the stored chain to reach that pivot's
  *     hash.
  */
sealed trait HeaderChainCheck:

  /** True when every stored header is validated against its parent, so the downloaded chain can anchor the pivot. */
  def validatesChain: Boolean

  /** True when the persisted pivot hash, not the downloaded chain, decides which header is the pivot: the consensus
    * layer chose it. A downloaded chain that does not reach it is downloaded again from where the two agree; the pivot
    * is never moved to the downloaded chain's header.
    */
  def trustsPinnedPivot: Boolean = false

  /** For logs. */
  def description: String

  /** Which headers of one response get their seal checked, by position. `headers` is ascending and contiguous, as the
    * download stores it; `target` is the download's target block (the SNAP pivot).
    */
  def sealPositions(headers: IndexedSeq[BlockHeader], target: BigInt): IndexedSeq[Boolean]

  /** `header` against `parent`, with the seal when `checkSeal`. */
  def check(header: BlockHeader, parent: BlockHeader, checkSeal: Boolean): Either[BlockHeaderError, BlockHeaderValid]

  /** `header` against `parent`, where `parentWeight` is the parent's chain weight as the download stores it (its
    * parent's weight plus its difficulty, from genesis). The download calls this one. Only [[ProofOfStake]] reads the
    * weight; the other checks are [[check]].
    */
  def checkLinked(
      header: BlockHeader,
      parent: BlockHeader,
      @annotation.unused parentWeight: ChainWeight,
      checkSeal: Boolean
  ): Either[BlockHeaderError, BlockHeaderValid] =
    check(header, parent, checkSeal)

  /** The checks that need no parent (extra data, gas used, and the seal on proof of work): a pivot header's cheap gate.
    */
  def validateHeaderOnly(header: BlockHeader): Either[BlockHeaderError, BlockHeaderValid]

object HeaderChainCheck:

  /** One seal is checked at a random position in every window of this many headers of a response: go-ethereum and
    * core-geth's `fsHeaderCheckFrequency` (eth/downloader/downloader.go), used by `HeaderChain.ValidateHeaderChain`.
    */
  val DefaultSealSampleWindow: Int = 100

  /** Uniform in `[0, bound)` from a CSPRNG, so a peer cannot tell which headers of a response will be seal-checked. */
  val secureRandomBelow: Int => Int =
    val rng = new SecureRandom()
    bound => rng.nextInt(bound)

  /** A chain with no terminal total difficulty is proof of work end to end: ETC, Mordor and the PoW test chains. */
  def isProofOfWork(blockchainConfig: BlockchainConfig): Boolean =
    blockchainConfig.terminalTotalDifficulty.isEmpty

  /** The log tag of every header rejection: the header download, the pivot bootstrap and the pivot gate. */
  val LogTag: String = "[HEADER-CHECK]"

  /** Where a header was rejected: the `phase` of the `chain.header.rejected` metric. */
  object Phase:
    val HeaderDownload: String = "header-download"
    val PivotBootstrap: String = "pivot-bootstrap"
    val PivotGate: String = "pivot-gate"
    val Finalisation: String = "finalisation"

  /** The `reason` of the `chain.header.rejected` metric for a header whose parent hash does not name the previous one.
    */
  val ParentLinkReason: String = "ParentLink"

  /** The `reason` of the `chain.header.rejected` metric when the downloaded chain's header at the pivot is not the
    * pinned pivot (a pivot the consensus layer chose).
    */
  val PinnedPivotReason: String = "PinnedPivot"

  /** The `reason` of the `chain.header.rejected` metric for a rule `error`: its name, so the label set stays bounded.
    */
  def rejectionReason(error: BlockHeaderError): String = error.getClass.getSimpleName.stripSuffix("$")

  case object LinkOnly extends HeaderChainCheck:
    val validatesChain: Boolean = false
    val description: String = "parent-hash link only"
    def sealPositions(headers: IndexedSeq[BlockHeader], target: BigInt): IndexedSeq[Boolean] =
      IndexedSeq.fill(headers.size)(false)
    def check(
        header: BlockHeader,
        parent: BlockHeader,
        checkSeal: Boolean
    ): Either[BlockHeaderError, BlockHeaderValid] =
      Right(BlockHeaderValid)
    def validateHeaderOnly(header: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)

  /** Every header is validated against its parent by `validator`, the block-import header validator (extra data
    * including the DAO fork rule, timestamp, difficulty, gas used and gas limit, number, fork fields, base fee). The
    * seal is checked on a sample (go-ethereum/core-geth's header-chain sampling, `HeaderChain.ValidateHeaderChain`):
    *   - one header at a random position in every window of `sealSampleWindow` headers of a response;
    *   - the last header of every response;
    *   - every header within `sealSampleWindow` blocks of the download target (the SNAP pivot).
    *
    * Unsampled headers still pass every other rule, difficulty included, against a parent that is itself validated back
    * to genesis, and their descendants' sampled seals commit to them through the parent hash.
    *
    * @param random
    *   uniform in `[0, bound)`; a CSPRNG in production ([[secureRandomBelow]]), a fixed sequence in tests.
    */
  final class Validated(
      val validator: BlockHeaderValidatorSkeleton,
      blockchainConfig: BlockchainConfig,
      val sealSampleWindow: Int = DefaultSealSampleWindow,
      random: Int => Int = secureRandomBelow
  ) extends HeaderChainCheck:
    require(sealSampleWindow >= 1, s"sealSampleWindow must be at least 1, was $sealSampleWindow")

    val validatesChain: Boolean = true

    def description: String =
      s"validated against the parent by ${validator.getClass.getSimpleName.stripSuffix("$")}, seal checked on one " +
        s"header in every $sealSampleWindow, the last of each response and the last $sealSampleWindow before the target"

    def sealPositions(headers: IndexedSeq[BlockHeader], target: BigInt): IndexedSeq[Boolean] =
      val n = headers.size
      val seal = Array.fill(n)(false)
      if n > 0 then
        var window = 0
        while window * sealSampleWindow < n do
          val r = random(sealSampleWindow)
          require(r >= 0 && r < sealSampleWindow, s"random($sealSampleWindow) returned $r")
          seal(math.min(window * sealSampleWindow + r, n - 1)) = true
          window += 1
        seal(n - 1) = true
        val forceFrom = target - sealSampleWindow // every header numbered above this is checked
        var i = 0
        while i < n do
          if headers(i).number.value > forceFrom then seal(i) = true
          i += 1
      seal.toIndexedSeq

    def check(
        header: BlockHeader,
        parent: BlockHeader,
        checkSeal: Boolean
    ): Either[BlockHeaderError, BlockHeaderValid] =
      rejectingThrows(header) {
        if checkSeal then validator.validate(header, parent)(using blockchainConfig)
        else validator.validateParentRules(header, parent)(using blockchainConfig)
      }

    def validateHeaderOnly(header: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
      rejectingThrows(header)(validator.validateHeaderOnly(header)(using blockchainConfig))

    /** A peer's header the validator cannot evaluate is rejected with the reason, not thrown into the actor that asked.
      * The Ethash seal check throws for a difficulty its target arithmetic does not handle (0 and 1 among them); block
      * import never reaches it with one, because the difficulty rule against the parent runs first, but the header-only
      * check runs the seal on a header with no parent.
      */
    private def rejectingThrows(header: BlockHeader)(
        validation: => Either[BlockHeaderError, BlockHeaderValid]
    ): Either[BlockHeaderError, BlockHeaderValid] =
      try validation
      catch
        case NonFatal(e) =>
          Left(
            BlockHeaderError.HeaderUnexpectedError(
              s"header ${header.idTag} (difficulty ${header.difficulty.value}) could not be validated: " +
                s"${e.getClass.getSimpleName}: ${e.getMessage}"
            )
          )

  /** Chains with a terminal total difficulty `terminalTotalDifficulty` (ETH, Sepolia; 0 on a chain that was proof of
    * stake from genesis). A header is checked by its difficulty and its parent's total difficulty (`parentWeight`):
    *   - after a proof-of-stake parent (difficulty 0) no proof-of-work header follows (go-ethereum
    *     `beacon.VerifyHeader`, `ErrInvalidTerminalBlock`);
    *   - a proof-of-work header (difficulty above 0) needs a parent below the terminal total difficulty (EIP-3675: the
    *     terminal block is the first at or above it, and its children are proof of stake). Its number must follow the
    *     parent's; nothing else is checked, because this client has no difficulty schedule for these chains' history;
    *   - a proof-of-stake header needs a parent at or above the terminal total difficulty, the field layout of its fork
    *     ([[BlockHeader.validateFieldCount]]) and the post-merge header rules against its parent
    *     ([[PoSBlockHeaderValidator]], the import validator's proof-of-stake rules).
    *
    * A post-merge header has no seal, so these rules check a header's shape and its consistency with its parent, not
    * who made it. The chain is authenticated by its link to the pivot's hash, which the consensus layer supplied:
    * finalisation holds until the download reaches that hash ([[trustsPinnedPivot]]).
    */
  final class ProofOfStake(blockchainConfig: BlockchainConfig, val terminalTotalDifficulty: BigInt)
      extends HeaderChainCheck:

    val validatesChain: Boolean = true
    override val trustsPinnedPivot: Boolean = true

    def description: String =
      s"post-merge header rules against the parent, the terminal total difficulty $terminalTotalDifficulty at the " +
        "transition, and parent links before it; the pivot's hash comes from the consensus layer"

    def sealPositions(headers: IndexedSeq[BlockHeader], target: BigInt): IndexedSeq[Boolean] =
      IndexedSeq.fill(headers.size)(false)

    /** Without the parent's total difficulty: the rules above but the two that read it, which is go-ethereum's
      * `beacon.VerifyHeader` (difficulty decides the era). The download calls [[checkLinked]].
      */
    def check(
        header: BlockHeader,
        parent: BlockHeader,
        checkSeal: Boolean
    ): Either[BlockHeaderError, BlockHeaderValid] =
      checkWithParentTd(header, parent, None)

    override def checkLinked(
        header: BlockHeader,
        parent: BlockHeader,
        parentWeight: ChainWeight,
        checkSeal: Boolean
    ): Either[BlockHeaderError, BlockHeaderValid] =
      checkWithParentTd(header, parent, Some(parentWeight.totalDifficulty.value))

    private def checkWithParentTd(
        header: BlockHeader,
        parent: BlockHeader,
        parentTd: Option[BigInt]
    ): Either[BlockHeaderError, BlockHeaderValid] =
      val proofOfStake = header.difficulty == Difficulty.Zero
      // Unknown (no weight): neither TD rule applies.
      val terminalReached = parentTd.map(_ >= terminalTotalDifficulty)
      if parent.difficulty == Difficulty.Zero && !proofOfStake then Left(BlockHeaderError.HeaderDifficultyError)
      else if !proofOfStake then
        if terminalReached.contains(true) then Left(BlockHeaderError.HeaderDifficultyError)
        else if header.number.value != parent.number.value + 1 then Left(BlockHeaderError.HeaderNumberError)
        else Right(BlockHeaderValid)
      else if terminalReached.contains(false) then Left(BlockHeaderError.HeaderDifficultyError)
      else
        for
          _ <- fieldLayout(header)
          _ <- PoSBlockHeaderValidator.validate(header, parent)(using blockchainConfig)
        yield BlockHeaderValid

    /** A proof-of-stake header: its field layout and the post-merge header-only rules. A proof-of-work header: nothing
      * (no rule of this chain's proof of work applies to a header alone here).
      */
    def validateHeaderOnly(header: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
      if header.difficulty != Difficulty.Zero then Right(BlockHeaderValid)
      else
        for
          _ <- fieldLayout(header)
          _ <- PoSBlockHeaderValidator.validateHeaderOnly(header)(using blockchainConfig)
        yield BlockHeaderValid

    private def fieldLayout(header: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
      BlockHeader
        .validateFieldCount(header, blockchainConfig)
        .left
        .map(_ => BlockHeaderError.HeaderExtraFieldsError(header.extraFields))
        .map(_ => BlockHeaderValid)

  /** The check for this chain. Chains with a terminal total difficulty get [[ProofOfStake]]. Proof-of-work chains get
    * [[Validated]] with `importValidator`, the validator block import uses (`Validators.blockHeaderValidator`); a
    * proof-of-work chain whose import validator is not a [[BlockHeaderValidatorSkeleton]] (test mode's) gets
    * [[LinkOnly]]: the caller logs that.
    */
  def forChain(blockchainConfig: BlockchainConfig, importValidator: BlockHeaderValidator): HeaderChainCheck =
    blockchainConfig.terminalTotalDifficulty match
      case Some(ttd) => new ProofOfStake(blockchainConfig, ttd)
      case None =>
        importValidator match
          case skeleton: BlockHeaderValidatorSkeleton => new Validated(skeleton, blockchainConfig)
          case _                                      => LinkOnly

  /** Whether SNAP finalisation must wait for the header download (the header hold, [[enforceHeaderHold]]): on
    * proof-of-work chains, and wherever the download's chain anchors the pivot.
    */
  def headerHoldRequired(blockchainConfig: BlockchainConfig, check: HeaderChainCheck): Boolean =
    isProofOfWork(blockchainConfig) || check.validatesChain

  /** The SNAP config with the header hold on, where it is required ([[headerHoldRequired]]; `proofOfWork` names the
    * proof-of-work case, the first one): `chain-download-enabled` and `defer-chain-backfill-until-state-complete` are
    * both forced on, because finalisation anchors the pivot to the downloaded, validated header chain and must wait for
    * it to reach the pivot. Returns the config and the keys that were set off (and are now ignored), for the caller's
    * warning. Otherwise: unchanged, no keys.
    */
  def enforceHeaderHold(cfg: SNAPSyncConfig, proofOfWork: Boolean): (SNAPSyncConfig, Seq[String]) =
    if !proofOfWork then (cfg, Nil)
    else
      val ignored =
        Seq("chain-download-enabled" -> cfg.chainDownloadEnabled)
          .appended("defer-chain-backfill-until-state-complete" -> cfg.deferChainBackfillUntilStateComplete)
          .collect { case (key, false) => key }
      (cfg.copy(chainDownloadEnabled = true, deferChainBackfillUntilStateComplete = true), ignored)
