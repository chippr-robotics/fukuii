package com.chipprbots.ethereum.consensus.mining

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.domain.Address

/** Thread-safe provider for the miner's coinbase address (etherbase).
  *
  * Allows runtime updates to the coinbase address via RPC methods like eth_setEtherbase,
  * while ensuring safe concurrent access from mining and RPC operations.
  *
  * @param initialCoinbase The initial coinbase address to use
  */
class CoinbaseProvider(initialCoinbase: Address) {
  private val coinbaseRef = new AtomicReference[Address](initialCoinbase)

  /** Get the current coinbase address.
    *
    * @return The current coinbase address
    */
  def get(): Address = coinbaseRef.get()

  /** Update the coinbase address.
    *
    * @param newCoinbase The new coinbase address to use
    */
  def update(newCoinbase: Address): Unit = {
    coinbaseRef.set(newCoinbase)
  }
}
