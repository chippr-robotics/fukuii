package com.chipprbots.ethereum.db.storage.pathstate

import com.chipprbots.ethereum.db.storage.MptStorage

/** What the build of a genesis state writes, on the unified layout (spec 018 data-model 5.4): every trie of the genesis
  * is built on [[storage]], each under its owner, and [[PathStateStorage.commitGenesis]] writes the nodes it ends with
  * as the rows of a datadir that held none.
  *
  * It is a block's staged layer over a view of nothing. The tries read back what they wrote, by hash, and nothing else;
  * what they replace on the way is recorded as replaced, so the build ends with one node for each position and no prior
  * for any. (The hash store's build drops the tries' removals instead, because two storage tries can share a node there
  * by its hash. Here each account's storage trie has rows of its own.)
  *
  * It can be made on any datadir, and is dropped unused on one that has its genesis: the build runs at every start, to
  * say what the genesis header is.
  */
final class GenesisLayer private[pathstate] ():

  private[pathstate] val changes = new StagedChanges

  /** The storage the genesis tries are built on. */
  val storage: MptStorage = PathMptStorage.staged(StateView.ofNothing, changes)
