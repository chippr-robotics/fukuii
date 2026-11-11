package com.chipprbots.ethereum.db.storage.pathstate

/** Proof that the caller holds the state lock (spec 018 data-model 7.0): the one lock under which every in-place batch
  * of the state is built and written.
  *
  * Some updates are only right when nothing else can be building one at the same time. The index of a height's
  * changeset records is read, extended and written back: two commits of sibling blocks doing that at once would each
  * write the list without the other's hash, and the lost record would never be pruned. A method that needs the lock
  * asks for this value, and only the lock can make one, so the compiler holds every caller to it.
  *
  * The lock itself arrives with the staged commit (C3b). Until then nothing in the node can call what asks for this.
  */
final class StateLockHeld private[pathstate] ()
