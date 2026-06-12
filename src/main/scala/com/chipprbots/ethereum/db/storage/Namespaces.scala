package com.chipprbots.ethereum.db.storage

object Namespaces {
  val ReceiptsNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('r'.toByte)
  val HeaderNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('h'.toByte)
  val BodyNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('b'.toByte)
  val NodeNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('n'.toByte)
  val CodeNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('c'.toByte)
  val ChainWeightNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('w'.toByte)
  val AppStateNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('s'.toByte)
  val KnownNodesNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('k'.toByte)
  val HeightsNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('i'.toByte)
  val FastSyncStateNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('f'.toByte)
  val TransactionMappingNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('l'.toByte)
  val BlockFirstSeenNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('m'.toByte)
  val FlatSlotNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('d'.toByte) // Flat storage slot data (Besu: ACCOUNT_STORAGE_STORAGE)
  val FlatAccountNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('a'.toByte) // Flat account data (Besu: ACCOUNT_INFO_STATE, geth 'a' convention)
  val HealingFrontierNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('g'.toByte) // Post-SNAP healing frontier (node hash -> pathset), Layer-2 resume
  val BfsQueueNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('q'.toByte) // BFS level queue (streaming frontier rebuild — OOM-safe at L7+)
  val SnapSyncProgressNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('p'.toByte) // SNAP download progress (stateRoot -> JSON cursors, account + storage)
  val StateTriePathNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('t'.toByte) // state trie nodes, path-keyed (PathScheme only)
  val StorageTriePathNamespace: IndexedSeq[Byte] =
    IndexedSeq[Byte]('u'.toByte) // storage trie nodes, path-keyed, scoped by accountHash (PathScheme only)

  val nsSeq: Seq[IndexedSeq[Byte]] = Seq(
    ReceiptsNamespace,
    HeaderNamespace,
    BodyNamespace,
    NodeNamespace,
    CodeNamespace,
    ChainWeightNamespace,
    AppStateNamespace,
    KnownNodesNamespace,
    HeightsNamespace,
    FastSyncStateNamespace,
    TransactionMappingNamespace,
    BlockFirstSeenNamespace,
    FlatSlotNamespace,
    FlatAccountNamespace,
    HealingFrontierNamespace,
    BfsQueueNamespace,
    SnapSyncProgressNamespace,
    StateTriePathNamespace,
    StorageTriePathNamespace
  )
}
