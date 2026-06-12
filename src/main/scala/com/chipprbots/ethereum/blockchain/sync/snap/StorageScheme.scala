package com.chipprbots.ethereum.blockchain.sync.snap

/** Trie node storage scheme for SNAP sync.
  *
  * HashScheme (default for ETC) keys nodes by their keccak256 hash — content-addressed, no pruning needed, no per-path
  * uniqueness constraint. PathScheme keys nodes by their nibble path — exactly one node per path slot, enabling inline
  * pruning but requiring boundary cleanup on resume.
  *
  * See [[SnapHashTrie]] and [[SnapPathTrie]] for the respective implementations.
  */
enum StorageScheme {

  /** Hash-keyed trie nodes. Default for ETC. Nodes are content-addressed; re-emitting a node is a no-op. */
  case Hash

  /** Path-keyed trie nodes. For ETH full nodes. Exactly one node per path slot; boundary cleanup required on resume. */
  case Path
}

object StorageScheme {

  /** Parse a HOCON config string (case-insensitive) to a [[StorageScheme]].
    *
    * Valid values: `"hash"` or `"path"`. Throws [[IllegalArgumentException]] on an unrecognised value.
    */
  def fromString(s: String): StorageScheme = s.toLowerCase match {
    case "hash" => Hash
    case "path" => Path
    case other  => throw new IllegalArgumentException(s"Unknown storage-scheme: '$other'. Valid values: hash, path")
  }
}
