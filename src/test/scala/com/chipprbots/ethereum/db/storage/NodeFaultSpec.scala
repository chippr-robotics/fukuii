package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.testing.Tags.*

/** The one judgement of a stored trie node (S-1): keccak first, then decode. `check` is what the background audit and
  * the heal coordinator's frontier walk believe; `provablyBad` is what a delete may take, which bytes that pass keccak
  * never are.
  */
class NodeFaultSpec extends AnyFlatSpec with Matchers:

  private val leaf: Array[Byte] =
    MptTraversals.encodeNode(LeafNode(ByteString(Array.fill[Byte](4)(3)), ByteString(Array.fill[Byte](40)(4))))
  private val leafHash: ByteString = ByteString(kec256(leaf))

  /** Bytes that hash to their key and are no trie node: what a peer would serve for that key too. */
  private val notANode: Array[Byte] = Array(0x01.toByte)
  private val notANodeHash: ByteString = ByteString(kec256(notANode))

  /** What the S-1 classes put under the leaf's key, as a store reads them back. */
  private val underLeafKey: Seq[(String, RawNodeRead)] = Seq(
    "a wrapper that does not unwrap" -> RawNodeRead.Undecodable,
    "0x01" -> RawNodeRead.Bytes(Array(0x01.toByte)),
    "0xc1" -> RawNodeRead.Bytes(Array(0xc1.toByte)),
    "0x80" -> RawNodeRead.Bytes(Array(0x80.toByte)),
    "another node's bytes" -> RawNodeRead.Bytes(
      MptTraversals.encodeNode(LeafNode(ByteString(Array.fill[Byte](4)(5)), ByteString(Array.fill[Byte](40)(6))))
    )
  )

  "NodeFault.check" should "give the node whose bytes hash to its key" taggedAs UnitTest in {
    NodeFault.check(leafHash, RawNodeRead.Bytes(leaf)).map(node => ByteString(node.hash)) shouldBe Right(leafHash)
  }

  it should "judge keccak before it decodes: bytes under another key fail their hash, whatever they decode to" taggedAs UnitTest in {
    underLeafKey.collect { case (name, raw: RawNodeRead.Bytes) =>
      name -> NodeFault.check(leafHash, raw).left.toOption
    } shouldBe underLeafKey.collect { case (name, _: RawNodeRead.Bytes) => name -> Some(NodeFault.BadKeccak) }
  }

  it should "call an entry that does not unwrap undecodable, an absent one absent, and bytes that pass keccak but are no node undecodable" taggedAs UnitTest in {
    NodeFault.check(leafHash, RawNodeRead.Undecodable) shouldBe Left(NodeFault.Undecodable)
    NodeFault.check(leafHash, RawNodeRead.Absent) shouldBe Left(NodeFault.Absent)
    NodeFault.check(notANodeHash, RawNodeRead.Bytes(notANode)) shouldBe Left(NodeFault.Undecodable)
  }

  "NodeFault.provablyBad" should "take every S-1 class, and nothing absent, good, or passing keccak" taggedAs UnitTest in {
    underLeafKey.filterNot { case (_, raw) => NodeFault.provablyBad(leafHash, raw) }.map(_._1) shouldBe empty
    NodeFault.provablyBad(leafHash, RawNodeRead.Absent) shouldBe false
    NodeFault.provablyBad(leafHash, RawNodeRead.Bytes(leaf)) shouldBe false
    withClue("bytes that pass keccak are the node, decoded or not: never deleted (2026-11-01): ") {
      NodeFault.provablyBad(notANodeHash, RawNodeRead.Bytes(notANode)) shouldBe false
    }
  }
