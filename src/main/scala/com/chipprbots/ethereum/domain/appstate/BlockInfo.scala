package com.chipprbots.ethereum.domain.appstate

import org.apache.pekko.util.ByteString

case class BlockInfo(hash: ByteString, number: BigInt)
