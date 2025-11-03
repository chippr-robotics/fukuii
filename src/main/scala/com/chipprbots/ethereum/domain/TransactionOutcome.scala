package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

sealed trait TransactionOutcome

case class HashOutcome(stateHash: ByteString) extends TransactionOutcome

case object SuccessOutcome extends TransactionOutcome

case object FailureOutcome extends TransactionOutcome
