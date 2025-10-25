package com.chipprbots.ethereum.jsonrpc

import monix.eval.Task

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.ProofAccount

object ProofServiceDummy extends ProofService {

  val EmptyAddress: Address = Address(Account.EmptyCodeHash)
  val EmptyProofAccount: ProofAccount = ProofAccount(
    EmptyAddress,
    Seq.empty,
    BigInt(42),
    Account.EmptyCodeHash,
    UInt256.Zero,
    Account.EmptyStorageRootHash,
    Seq.empty
  )
  val EmptyProofResponse: GetProofResponse = GetProofResponse(EmptyProofAccount)

  override def getProof(req: GetProofRequest): ServiceResponse[GetProofResponse] =
    Task.now(Right(EmptyProofResponse))
}
