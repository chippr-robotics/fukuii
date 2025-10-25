package com.chipprbots.ethereum

package object faucet {
  sealed trait FaucetStatus
  object FaucetStatus {
    case object FaucetUnavailable extends FaucetStatus
    case object WalletAvailable extends FaucetStatus
  }
}
