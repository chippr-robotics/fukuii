package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.jsonrpc.PersonalService._

/** Manual test double for PersonalServiceAPI.
  *
  * Replaces ScalaMock `mock[PersonalServiceAPI]` because ScalaMock 6.0.0 with Scala 3.3.4 generates inconsistent
  * internal method name suffixes when mock creation and `.expects()` occur in different compilation units.
  *
  * Usage: assign the `*Fn` var for each method that the test exercises. Unassigned methods raise NotImplementedError.
  */
class TestPersonalService extends PersonalServiceAPI {

  var importRawKeyFn: ImportRawKeyRequest => ServiceResponse[ImportRawKeyResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.importRawKey not configured"))

  var newAccountFn: NewAccountRequest => ServiceResponse[NewAccountResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.newAccount not configured"))

  var listAccountsFn: ListAccountsRequest => ServiceResponse[ListAccountsResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.listAccounts not configured"))

  var unlockAccountFn: UnlockAccountRequest => ServiceResponse[UnlockAccountResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.unlockAccount not configured"))

  var lockAccountFn: LockAccountRequest => ServiceResponse[LockAccountResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.lockAccount not configured"))

  var signFn: SignRequest => ServiceResponse[SignResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.sign not configured"))

  var ecRecoverFn: EcRecoverRequest => ServiceResponse[EcRecoverResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.ecRecover not configured"))

  var sendTransactionWithPassphraseFn: SendTransactionWithPassphraseRequest => ServiceResponse[SendTransactionWithPassphraseResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.sendTransaction(WithPassphrase) not configured"))

  var sendTransactionFn: SendTransactionRequest => ServiceResponse[SendTransactionResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.sendTransaction not configured"))

  var sendIeleTransactionFn: SendIeleTransactionRequest => ServiceResponse[SendTransactionResponse] =
    _ => IO.raiseError(new NotImplementedError("TestPersonalService.sendIeleTransaction not configured"))

  override def importRawKey(req: ImportRawKeyRequest): ServiceResponse[ImportRawKeyResponse] = importRawKeyFn(req)
  override def newAccount(req: NewAccountRequest): ServiceResponse[NewAccountResponse] = newAccountFn(req)
  override def listAccounts(request: ListAccountsRequest): ServiceResponse[ListAccountsResponse] = listAccountsFn(request)
  override def unlockAccount(request: UnlockAccountRequest): ServiceResponse[UnlockAccountResponse] = unlockAccountFn(request)
  override def lockAccount(request: LockAccountRequest): ServiceResponse[LockAccountResponse] = lockAccountFn(request)
  override def sign(request: SignRequest): ServiceResponse[SignResponse] = signFn(request)
  override def ecRecover(req: EcRecoverRequest): ServiceResponse[EcRecoverResponse] = ecRecoverFn(req)
  override def sendTransaction(request: SendTransactionWithPassphraseRequest): ServiceResponse[SendTransactionWithPassphraseResponse] = sendTransactionWithPassphraseFn(request)
  override def sendTransaction(request: SendTransactionRequest): ServiceResponse[SendTransactionResponse] = sendTransactionFn(request)
  override def sendIeleTransaction(request: SendIeleTransactionRequest): ServiceResponse[SendTransactionResponse] = sendIeleTransactionFn(request)
}
