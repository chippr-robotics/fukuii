package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData._
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags._

/** ETHPackets.NodeData encode/decode tests.
  *
  * ETH63's NodeData had getMptNode() for decoding MPT nodes from raw bytes. ETHPackets.NodeData stores raw bytes
  * (Seq[ByteString]) for lazy decoding. The ETH68 message decoder rejects GetNodeData/NodeData (EIP-4938). These tests
  * verify the raw encode/decode round-trip only.
  */
class NodeDataSpec extends AnyFlatSpec with Matchers {

  val exampleHash: ByteString = ByteString(kec256(Hex.decode("ab" * 32)))
  val exampleValue: ByteString = ByteString(Hex.decode("abcdee"))

  val nodeData: NodeData = NodeData(Seq(exampleHash, exampleValue))

  "NodeData" should "encode to RLP bytes" taggedAs (UnitTest, NetworkTest) in {
    val bytes: Array[Byte] = nodeData.toBytes
    bytes.length should be > 0
  }

  it should "round-trip encode/decode" taggedAs (UnitTest, NetworkTest) in {
    val bytes: Array[Byte] = nodeData.toBytes
    val decoded: NodeData = bytes.toNodeData
    decoded.values should equal(nodeData.values)
  }

  it should "decode empty NodeData" taggedAs (UnitTest, NetworkTest) in {
    val empty = NodeData(Seq.empty)
    val decoded = empty.toBytes.toNodeData
    decoded.values shouldBe empty.values
  }

  it should "preserve multiple values in round-trip" taggedAs (UnitTest, NetworkTest) in {
    val multiNode = NodeData(Seq(exampleHash, exampleValue, ByteString(Hex.decode("cafebabe"))))
    val decoded = multiNode.toBytes.toNodeData
    decoded.values should have size 3
    decoded.values should equal(multiNode.values)
  }
}
