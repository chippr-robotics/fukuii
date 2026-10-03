package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import java.util.zip.GZIPInputStream

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*

import com.chipprbots.ethereum.domain.BlockAccessList.*

/** Live EIP-7928 access lists for specs that serve one: `src/test/resources/eip7928/plataberget-bal-<number>.json.gz`,
  * Platåberget blocks fetched from `rpc.plataberget.ethpandaops.io` (reth). BlockAccessListSpec authenticates each
  * file: its header fields rebuild to the canonical block hash, and its list encodes to bytes whose keccak256 is that
  * header's `blockAccessListHash`. So a spec that serves [[LiveAccessList.bytes]] and finds that keccak256 serves the
  * list the network committed to.
  */
object PlatabergetBalVectors:

  /** Block 275,654: 10 accounts, 65,994 bytes of RLP holding one 65,536-byte code change, so the list and that item
    * both take three-byte RLP length prefixes.
    */
  val Block275654: Int = 275654

  /** A live block's hash, the `blockAccessListHash` its header commits to, and its access list. */
  final case class LiveAccessList(blockHash: ByteString, blockAccessListHash: ByteString, accessList: BlockAccessList):
    /** The list's canonical RLP: what fukuii stores and serves. */
    val bytes: ByteString = accessList.toBytes

  def load(block: Int): LiveAccessList =
    val json = resource(block)
    LiveAccessList(
      blockHash = bytes(json \ "header" \ "hash"),
      blockAccessListHash = bytes(json \ "header" \ "blockAccessListHash"),
      accessList = accessList(json \ "blockAccessList")
    )

  private def resource(block: Int): JValue =
    val in = new GZIPInputStream(getClass.getResourceAsStream(s"/eip7928/plataberget-bal-$block.json.gz"))
    try parse(new String(in.readAllBytes(), "UTF-8"))
    finally in.close()

  private def str(v: JValue): String = v.values.toString
  private def bytes(v: JValue): ByteString = ByteString(Hex.decode(str(v).stripPrefix("0x")))
  private def quantity(v: JValue): BigInt = BigInt(str(v).stripPrefix("0x"), 16)
  private def arr(v: JValue): List[JValue] = v match
    case JArray(items) => items
    case other         => throw new IllegalArgumentException(s"expected a JSON array, got $other")

  /** reth's `eth_getBlockAccessList` JSON (as BlockAccessListSpec reads it): slots and storage values as 32-byte words,
    * the rest as quantities.
    */
  private def accessList(json: JValue): BlockAccessList =
    BlockAccessList(arr(json).map { a =>
      AccountChanges(
        address = Address(bytes(a \ "address")),
        storageChanges = arr(a \ "storageChanges").map { s =>
          SlotChanges(
            UInt256(quantity(s \ "key")),
            arr(s \ "changes").map(c => StorageChange(quantity(c \ "index").toLong, UInt256(quantity(c \ "value"))))
          )
        },
        storageReads = arr(a \ "storageReads").map(r => UInt256(quantity(r))),
        balanceChanges = arr(a \ "balanceChanges").map(c =>
          BalanceChange(quantity(c \ "index").toLong, UInt256(quantity(c \ "value")))
        ),
        nonceChanges =
          arr(a \ "nonceChanges").map(c => NonceChange(quantity(c \ "index").toLong, quantity(c \ "value"))),
        codeChanges = arr(a \ "codeChanges").map(c => CodeChange(quantity(c \ "index").toLong, bytes(c \ "code")))
      )
    })
