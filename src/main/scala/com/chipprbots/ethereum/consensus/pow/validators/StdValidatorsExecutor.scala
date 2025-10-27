package com.chipprbots.ethereum.consensus.pow.validators

import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator

/** Implements validators that adhere to the PoW-specific
  * [[com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor]] interface.
  */
final class StdValidatorsExecutor private[validators] (
    val blockValidator: BlockValidator,
    val blockHeaderValidator: BlockHeaderValidator,
    val signedTransactionValidator: SignedTransactionValidator,
    val ommersValidator: OmmersValidator
) extends ValidatorsExecutor
