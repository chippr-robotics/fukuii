package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.mpt.NibblePath

/** Where a stored trie node sits (spec 018 data-model 2 and 3): the trie it belongs to and its path in that trie.
  *
  * `owner` is `None` for the account trie and `Some(keccak256(address))` for that account's storage trie. `hp` is the
  * path hex-prefix encoded as a non-leaf, which is the key of the row in CF `t`, and the part after the owner in CF
  * `u`.
  *
  * The path is held encoded, as a `ByteString`, so that two keys are equal when they name the same position: a
  * [[com.chipprbots.ethereum.mpt.NibblePath]] is an opaque array and compares by identity.
  */
final case class PositionKey(owner: Option[ByteString], hp: ByteString):
  require(owner.forall(_.length == PositionKey.OwnerLength), "the owner of a storage trie is a 32-byte account hash")

  /** The key of this position's row in its column family: `hp` in CF `t`, `owner ++ hp` in CF `u`. */
  def rowKey: Array[Byte] = owner.fold(hp.toArray)(o => (o ++ hp).toArray)

  def path: NibblePath = NibblePath.fromHexPrefix(hp.toArray)

  def show: String =
    owner.fold("the account trie")(o => s"the storage trie of ${Hex.toHexString(o.toArray)}") + s" at [${path.show}]"

object PositionKey:
  val OwnerLength: Int = 32

  def at(owner: Option[ByteString], path: NibblePath): PositionKey = PositionKey(owner, path.key)

  /** The root of the account trie: the one position whose node is found by its hash alone. */
  val AccountRoot: PositionKey = at(None, NibblePath.Empty)
