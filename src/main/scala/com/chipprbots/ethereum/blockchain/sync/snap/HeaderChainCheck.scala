package com.chipprbots.ethereum.blockchain.sync.snap

import java.security.SecureRandom

import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

/** How the SNAP header download ([[ChainDownloader]]) checks the headers it stores, chosen once per chain when SNAP
  * starts ([[HeaderChainCheck.forChain]]).
  *
  *   - [[HeaderChainCheck.LinkOnly]]: each header must name the previous one as its parent, and nothing else is
  *     checked. Chains with a terminal total difficulty (ETH, Sepolia), whose SNAP pivot comes from the consensus
  *     layer.
  *   - [[HeaderChainCheck.Validated]]: proof-of-work chains (ETC, Mordor). Every header is also validated against its
  *     parent by the block-import header validator, and the stored chain is what SNAP finalisation anchors the pivot to
  *     ([[PivotAnchor]]).
  */
sealed trait HeaderChainCheck:

  /** True when every stored header is validated against its parent, so the downloaded chain can anchor the pivot. */
  def validatesChain: Boolean

  /** For logs. */
  def description: String

  /** Which headers of one response get their seal checked, by position. `headers` is ascending and contiguous, as the
    * download stores it; `target` is the download's target block (the SNAP pivot).
    */
  def sealPositions(headers: IndexedSeq[BlockHeader], target: BigInt): IndexedSeq[Boolean]

  /** `header` against `parent`, with the seal when `checkSeal`. */
  def check(header: BlockHeader, parent: BlockHeader, checkSeal: Boolean): Either[BlockHeaderError, BlockHeaderValid]

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

  /** The check for this chain. Proof-of-work chains get [[Validated]] with `importValidator`, the validator block
    * import uses (`Validators.blockHeaderValidator`); chains with a terminal total difficulty get [[LinkOnly]]. A
    * proof-of-work chain whose import validator is not a [[BlockHeaderValidatorSkeleton]] (test mode's) gets
    * [[LinkOnly]] too: the caller logs that.
    */
  def forChain(blockchainConfig: BlockchainConfig, importValidator: BlockHeaderValidator): HeaderChainCheck =
    importValidator match
      case skeleton: BlockHeaderValidatorSkeleton if isProofOfWork(blockchainConfig) =>
        new Validated(skeleton, blockchainConfig)
      case _ => LinkOnly
