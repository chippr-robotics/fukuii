package com.chipprbots.ethereum.domain.branch

import org.apache.pekko.util.ByteString

sealed trait Branch

case class BestBranch(tipBlockHash: ByteString, tipBlockNumber: BigInt) extends Branch

case object EmptyBranch extends Branch
