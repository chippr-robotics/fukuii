package com.chipprbots.ethereum.sync.util

import java.net.InetSocketAddress
import java.net.ServerSocket

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

object SyncCommonItSpec {
  val IdentityUpdate: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy = (_, world) => world

  def randomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try new InetSocketAddress("localhost", s.getLocalPort)
    finally s.close()
  }

  final case class BlockchainState(
      bestBlock: Block,
      currentWorldState: InMemoryWorldStateProxy,
      currentWeight: ChainWeight
  )
}
