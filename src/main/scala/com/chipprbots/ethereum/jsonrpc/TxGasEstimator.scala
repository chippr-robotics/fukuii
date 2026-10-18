package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.jsonrpc.EthInfoService.CallRequest

/** The gas limit eth_sendTransaction / eth_fillTransaction give a request without `gas`: go-ethereum `setDefaults`
  * calls `DoEstimateGas` on the arguments as filled so far, against the latest block (transaction_args.go:166-191).
  */
trait TxGasEstimator:
  def estimateGas(args: TxArgs, head: BlockHeader): IO[Either[JsonRpcError, BigInt]]

/** [[TxGasEstimator]] backed by eth_estimateGas ([[EthInfoService.estimateGas]]), the typed call path of #1629 S3: the
  * access list, blob hashes and authorization list are priced and applied by the estimator itself.
  *
  * The estimate sees the fields geth's `setDefaults` copies into its `callArgs` (from, to, gasPrice, maxFeePerGas,
  * maxPriorityFeePerGas, value, data, accessList, maxFeePerBlobGas, blobVersionedHashes, authorizationList), plus
  * fukuii's explicit `type`; not the nonce, gas, chain id or the blob sidecar.
  */
final class EthInfoTxGasEstimator(ethInfoService: EthInfoService) extends TxGasEstimator:

  def estimateGas(args: TxArgs, head: BlockHeader): IO[Either[JsonRpcError, BigInt]] =
    val callArgs = args.copy(
      gas = None,
      nonce = None,
      chainId = None,
      data = Some(args.callData),
      input = None,
      blobs = None,
      commitments = None,
      proofs = None
    )
    ethInfoService.estimateGas(CallRequest(callArgs, BlockParam.Latest)).map(_.map(_.gas))
