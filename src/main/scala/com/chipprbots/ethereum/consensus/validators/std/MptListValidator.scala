package com.chipprbots.ethereum.consensus.validators
package std

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.decode
import com.chipprbots.ethereum.rlp.encode

object MptListValidator {

  lazy val intByteArraySerializable: ByteArraySerializable[Int] = new ByteArraySerializable[Int] {
    override def fromBytes(bytes: Array[Byte]): Int = decode[Int](bytes)
    override def toBytes(input: Int): Array[Byte] = encode(input)
  }

  /** This function validates if a lists matches a Mpt Hash. To do so it inserts into an ephemeral MPT (itemIndex, item)
    * tuples and validates the resulting hash
    *
    * @param hash
    *   Hash to expect
    * @param toValidate
    *   Items to validate and should match the hash
    * @param vSerializable
    *   [[com.chipprbots.ethereum.mpt.ByteArraySerializable]] to encode Items
    * @tparam K
    *   Type of the items cointained within the Sequence
    * @return
    *   true if hash matches trie hash, false otherwise
    */
  def isValid[K](hash: Array[Byte], toValidate: Seq[K], vSerializable: ByteArraySerializable[K]): Boolean = {
    val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
    val trie = MerklePatriciaTrie[Int, K](
      source = stateStorage
    )(intByteArraySerializable, vSerializable)
    val trieRoot = toValidate.zipWithIndex.foldLeft(trie)((trie, r) => trie.put(r._2, r._1)).getRootHash
    hash.sameElements(trieRoot)
  }
}
