package com.chipprbots.ethereum.consensus.mess

/** ECIP-1100: MESS (Modified Exponential Subjective Scoring).
  *
  * Anti-reorg protection: large reorgs require exponentially higher TD to override the local chain. Uses a cubic
  * polynomial to compute an "antigravity" multiplier (1x-31x) based on time since common ancestor. Reorgs under ~200s
  * are unaffected; peaks at ~7 hours (31x TD required).
  *
  * Polynomial: {{{ def get_curve_function_numerator(x): xcap = 25132 ampl = 15 height = 128 * (ampl * 2) if x > xcap: x
  * \= xcap return 128 + (3 * x**2 - 2 * x**3 // xcap) * height // xcap ** 2 }}}
  *
  * Activation/deactivation blocks:
  *   - ETC mainnet: activated 11,380,000, deactivated 19,250,000 (Spiral)
  *   - Mordor: activated 2,380,000, deactivated 10,400,000
  *
  * @see
  *   [[https://ecips.ethereumclassic.org/ECIPs/ecip-1100 ECIP-1100]]
  * @see
  *   [[https://github.com/ethereumclassic/ECIPs/issues/374#issuecomment-694156719 Polynomial specification]]
  */
object ArtificialFinality {

  /** CURVE_FUNCTION_DENOMINATOR = 128 */
  private val Denominator: BigInt = 128

  /** xcap = 25132 = floor(8000 * pi) */
  private val Xcap: BigInt = 25132

  /** ampl = 15 */
  private val Amplitude: BigInt = 15

  /** height = DENOMINATOR * (ampl * 2) = 128 * 30 = 3840 */
  private val Height: BigInt = Denominator * Amplitude * 2

  /** Compute the ECBP-1100 polynomial value for the given time delta.
    *
    * This is the "antigravity" curve: a cubic polynomial that starts at Denominator (128, i.e. 1x multiplier) and rises
    * to Denominator + Height (128 + 3840 = 3968, i.e. ~31x multiplier).
    *
    * @param timeDelta
    *   time in seconds between current head and common ancestor
    * @return
    *   numerator value (denominator is always 128)
    */
  def polynomialV(timeDelta: BigInt): BigInt = {
    val x = if (timeDelta > Xcap) Xcap else timeDelta

    // 3 * x^2
    val term1 = x.pow(2) * 3

    // 2 * x^3 / xcap
    val term2 = x.pow(3) * 2 / Xcap

    // (3 * x^2 - 2 * x^3 / xcap) * height / xcap^2
    val result = (term1 - term2) * Height / Xcap.pow(2)

    // DENOMINATOR + result
    Denominator + result
  }

  /** Check if a proposed reorg should be rejected by MESS.
    *
    * A reorg is rejected if: proposedSubchainTD * DENOMINATOR < polynomialV(timeDelta) * localSubchainTD
    *
    * @param timeDeltaSeconds
    *   time in seconds between current head and common ancestor (block timestamps)
    * @param localSubchainTD
    *   total difficulty of local chain segment (from common ancestor to current head)
    * @param proposedSubchainTD
    *   total difficulty of proposed chain segment (from common ancestor to proposed head)
    * @return
    *   true if the reorg should be REJECTED (proposed chain doesn't have enough gravity)
    */
  def shouldRejectReorg(
      timeDeltaSeconds: Long,
      localSubchainTD: BigInt,
      proposedSubchainTD: BigInt
  ): Boolean = {
    val eq = polynomialV(BigInt(timeDeltaSeconds))

    // want = polynomialV(timeDelta) * localSubchainTD
    val want = eq * localSubchainTD

    // got = proposedSubchainTD * DENOMINATOR
    val got = proposedSubchainTD * Denominator

    // Reject if got < want (proposed chain doesn't meet the antigravity threshold)
    got < want
  }
}
