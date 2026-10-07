package com.chipprbots.ethereum.consensus.blocks

import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator

/** Proposer-side gas-limit convergence, ported from go-ethereum `core.CalcGasLimit`.
  *
  * {{{
  * delta := parentGasLimit/GasLimitBoundDivisor - 1
  * limit := parentGasLimit
  * if limit < desiredLimit { limit = min(parentGasLimit+delta, desiredLimit) }
  * if limit > desiredLimit { limit = max(parentGasLimit-delta, desiredLimit) }
  * }}}
  *
  * The `-1` in `delta` is load-bearing: the consensus bound is a STRICT inequality (`|child - parent| < parent/1024`),
  * so a proposer stepping by exactly `parent/1024` would emit a header its own validator rejects. go-ethereum,
  * core-geth and besu all subtract one for the same reason.
  *
  * Single definition on purpose. [[BlockGeneratorSkeleton.calculateGasLimit]] (the ETC miner) and the ETH testing block
  * builder both call this; a second copy that drifts would make one of the two produce blocks the other refuses.
  */
object GasLimitCalculator:

  /** @param parentGas
    *   the parent header's gasLimit
    * @param target
    *   the gas limit the proposer is converging toward (go-ethereum's `--miner.gaslimit` / `GasCeil`)
    * @return
    *   the child block's gasLimit
    */
  def calcGasLimit(parentGas: BigInt, target: BigInt): BigInt =
    val delta = parentGas / BlockHeaderValidator.GasLimitBoundDivisor - 1
    if parentGas < target then
      val n = parentGas + delta; if n > target then target else n
    else if parentGas > target then
      val n = parentGas - delta; if n < target then target else n
    else parentGas
