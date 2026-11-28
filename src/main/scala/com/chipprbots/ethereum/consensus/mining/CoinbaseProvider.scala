package com.chipprbots.ethereum.consensus.mining

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.domain.Address

/** Thread-safe provider for the miner's coinbase address (etherbase).
  *
  * Allows runtime updates to the coinbase address via RPC methods like miner_setEtherbase, while ensuring safe
  * concurrent access from mining and RPC operations.
  *
  * @param initialCoinbase
  *   The initial coinbase address to use
  * @param initiallyChosen
  *   whether `initialCoinbase` is one an operator chose: false for the shipped default of `conf/base/mining.conf`
  *   ([[MiningConfig.DefaultCoinbase]]), which no one owns
  */
class CoinbaseProvider(initialCoinbase: Address, initiallyChosen: Boolean = true):
  private val coinbaseRef = new AtomicReference[Address](initialCoinbase)
  @volatile private var chosen: Boolean = initiallyChosen

  /** Get the current coinbase address.
    *
    * @return
    *   The current coinbase address
    */
  def get(): Address = coinbaseRef.get()

  /** Update the coinbase address.
    *
    * @param newCoinbase
    *   The new coinbase address to use
    */
  def update(newCoinbase: Address): Unit =
    coinbaseRef.set(newCoinbase)
    chosen = true

  /** The etherbase of the blocks this node counts as its own (B-284, the tie rule's `preserve`): the coinbase when an
    * operator chose it (configured, or set by `miner_setEtherbase`) or when this node mines (`miningEnabled`, whose
    * blocks carry the coinbase, the default included). None otherwise, and no block is this node's own: as core-geth's
    * `isLocalBlock`, whose `Etherbase()` fails with no etherbase set (forge N-c). The shipped default is no one's: on a
    * node that does not mine, a block another sealed to it is not kept or taken on a tie for it.
    */
  def ownBlocksEtherbase(miningEnabled: Boolean): Option[Address] =
    Option.when(miningEnabled || chosen)(get())

object CoinbaseProvider:

  /** The node's provider: its configured coinbase, counted as chosen unless it is the shipped default. HOCON does not
    * say whether a key was set by the operator or by the base configuration, so the default value stands for "not set";
    * an operator who configures that very address is taken as not having chosen one, which only matters on a node that
    * does not mine.
    */
  def of(miningConfig: MiningConfig): CoinbaseProvider =
    new CoinbaseProvider(miningConfig.coinbase, miningConfig.coinbase != MiningConfig.DefaultCoinbase)
