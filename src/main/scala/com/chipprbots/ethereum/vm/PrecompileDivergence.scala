package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import io.micrometer.core.instrument.Counter

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

/** The divergence signal of a failed precompile with no go-ethereum reason (B-176).
  *
  * `PrecompiledContract.failureReason` answers [[PrecompiledContracts.UnexplainedFailure]] ("precompiled contract
  * failed") for an input that none of go-ethereum's checks rejects. go-ethereum runs such an input, so `exec` failing
  * it means fukuii and go-ethereum disagree about it (a library that rejects what go-ethereum's accepts, for one). The
  * RPC still answers it, but the operator is told: one WARN, and a counter for the dashboard.
  *
  * Only the text of an error is involved ([[ProgramErrorText.vmErrorMessage]]): the RPC and the tracers ask for it,
  * block import never does, and nothing here changes a result.
  */
object PrecompileDivergence extends MetricsContainer with Logger:

  /** The counter's name, under the node's metrics prefix. */
  val UnexplainedCounterName: String = "json.rpc.precompile.unexplained.counter"

  final lazy val UnexplainedCounter: Counter = metrics.counter(UnexplainedCounterName)

  /** The contract's name as the log gives it: its object's simple name. */
  def contractName(contract: PrecompiledContracts.PrecompiledContract): String =
    contract.getClass.getSimpleName.stripSuffix("$")

  /** Counts and logs `contract` failing `input` with no go-ethereum reason. The input itself is never logged (it can be
    * large and is the caller's data): its length and keccak-256.
    */
  def unexplained(contract: PrecompiledContracts.PrecompiledContract, input: ByteString): Unit =
    UnexplainedCounter.increment()
    log.warn(
      "Precompile failure with no go-ethereum reason (fukuii rejects an input go-ethereum's checks accept): " +
        "contract={} inputLength={} inputKeccak=0x{}",
      contractName(contract),
      input.length,
      Hex.toHexString(kec256(input.toArray))
    )
