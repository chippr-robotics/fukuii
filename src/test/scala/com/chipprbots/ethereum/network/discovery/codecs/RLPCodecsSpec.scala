package com.chipprbots.ethereum.network.discovery.codecs

import java.net.InetAddress

import scala.reflect.ClassTag
import scala.util.Random

import _root_.com.chipprbots.ethereum.rlp.RLPException
import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.hash.Hash
import org.scalactic.Equality
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.Codec
import scodec.bits.BitVector

import com.chipprbots.ethereum.network.discovery.Secp256k1SigAlg
import com.chipprbots.ethereum.rlp.RLPDecoder
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPEncoder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.Ping
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.Pong
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.FindNode
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.Neighbors
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.ENRRequest
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.ENRResponse

class RLPCodecsSpec extends AnyFlatSpec with Matchers {
  import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
  import com.chipprbots.ethereum.rlp.RLPImplicits.given
  import RLPCodecs.given

  implicit val sigalg: Secp256k1SigAlg = new Secp256k1SigAlg()

  implicit val packetCodec: Codec[Packet] =
    Packet.packetCodec(allowDecodeOverMaxPacketSize = false)

  val localhost: InetAddress = InetAddress.getByName("127.0.0.1")

  def randomBytes(n: Int): BitVector = {
    val size = Random.nextInt(n)
    val bytes = Array.ofDim[Byte](size)
    Random.nextBytes(bytes)
    BitVector(bytes)
  }

  behavior.of("RLPCodecs")

  it should "encode a Ping with an ENR as 5 items" in {
    val ping = Payload.Ping(
      version = 4,
      from = Node.Address(localhost, 30000, 40000),
      to = Node.Address(localhost, 30001, 0),
      expiration = System.currentTimeMillis,
      enrSeq = Some(1)
    )

    val rlp = RLPEncoder.encode(ping)

    rlp match {
      case list: RLPList =>
        list.items should have size 5
        list.items.last shouldBe an[RLPValue]
      case other =>
        fail(s"Expected RLPList; got $other")
    }

    RLPDecoder.decode[Payload.Ping](rlp) shouldBe ping
  }

  it should "encode a Ping without an ENR as 4 items" in {
    val ping = Payload.Ping(
      version = 4,
      from = Node.Address(localhost, 30000, 40000),
      to = Node.Address(localhost, 30001, 0),
      expiration = System.currentTimeMillis,
      enrSeq = None
    )

    val rlp = RLPEncoder.encode(ping)

    rlp match {
      case list: RLPList =>
        list.items should have size 4
      case other =>
        fail(s"Expected RLPList; got $other")
    }

    RLPDecoder.decode[Payload.Ping](rlp) shouldBe ping
  }

  it should "reject a Node.Address with more than 3 fields" in {
    val rlp = RLPList(
      localhost,
      123,
      456,
      789
    )

    an[RLPException] should be thrownBy {
      RLPDecoder.decode[Node.Address](rlp)
    }
  }

  it should "reject a Node with more than 4 fields" in {
    val rlp = RLPList(
      localhost,
      123,
      456,
      randomBytes(64),
      "only Payloads accept extra fields"
    )

    an[RLPException] should be thrownBy {
      RLPDecoder.decode[Node.Address](rlp)
    }
  }

  // The following tests demonstrate what each payload looks like when encoded to RLP,
  // because the auto-derivation makes it opaque.
  abstract class RLPFixture[T <: Payload: RLPEncoder: RLPDecoder: ClassTag] {
    // Structrual equality checker for RLPEncodeable.
    // It has different wrappers for items based on whether it was hand crafted or generated
    // by codecs, and the RLPValue has mutable arrays inside.
    implicit val eqRLPList: Equality[RLPEncodeable] = new Equality[RLPEncodeable] {
      override def areEqual(a: RLPEncodeable, b: Any): Boolean =
        (a, b) match {
          case (a: RLPList, b: RLPList) =>
            a.items.size == b.items.size && a.items.zip(b.items).forall { case (a, b) =>
              areEqual(a, b)
            }
          case (a: RLPValue, b: RLPValue) =>
            a.bytes.sameElements(b.bytes)
          case _ =>
            false
        }
    }

    def name: String = implicitly[ClassTag[T]].runtimeClass.getSimpleName

    def p: T
    def e: RLPEncodeable

    def testEncode: Assertion = RLPEncoder.encode(p) should equal(e)
    def testDecode: Assertion = RLPDecoder.decode[T](e) should equal(p)
  }

  val examples: List[RLPFixture[_ <: Payload]] = List(
    new RLPFixture[Payload.Ping] {
      override val p: Ping = Payload.Ping(
        version = 4,
        from = Node.Address(localhost, 30000, 40000),
        to = Node.Address(localhost, 30001, 0),
        expiration = System.currentTimeMillis,
        enrSeq = Some(1)
      )

      override val e: RLPEncodeable = RLPList(
        p.version,
        RLPList(p.from.ip, p.from.udpPort, p.from.tcpPort),
        RLPList(p.to.ip, p.to.udpPort, p.to.tcpPort),
        p.expiration,
        p.enrSeq.get
      )
    },
    new RLPFixture[Payload.Pong] {
      override val p: Pong = Payload.Pong(
        to = Node.Address(localhost, 30001, 0),
        pingHash = Hash(randomBytes(32)),
        expiration = System.currentTimeMillis,
        enrSeq = Some(1)
      )

      override val e: RLPEncodeable = RLPList(
        RLPList(
          p.to.ip,
          p.to.udpPort,
          p.to.tcpPort
        ),
        p.pingHash,
        p.expiration,
        p.enrSeq.get
      )
    },
    new RLPFixture[Payload.FindNode] {
      override val p: FindNode = Payload.FindNode(
        target = PublicKey(randomBytes(64)),
        expiration = System.currentTimeMillis
      )

      override val e: RLPEncodeable = RLPList(p.target, p.expiration)
    },
    new RLPFixture[Payload.Neighbors] {
      override val p: Neighbors = Payload.Neighbors(
        nodes = List(
          Node(id = PublicKey(randomBytes(64)), address = Node.Address(localhost, 30001, 40001)),
          Node(id = PublicKey(randomBytes(64)), address = Node.Address(localhost, 30002, 40002))
        ),
        expiration = System.currentTimeMillis
      )

      override val e: RLPEncodeable = RLPList(
        RLPList(
          RLPList(p.nodes(0).address.ip, p.nodes(0).address.udpPort, p.nodes(0).address.tcpPort, p.nodes(0).id),
          RLPList(p.nodes(1).address.ip, p.nodes(1).address.udpPort, p.nodes(1).address.tcpPort, p.nodes(1).id)
        ),
        p.expiration
      )
    },
    new RLPFixture[Payload.ENRRequest] {
      override val p: ENRRequest = Payload.ENRRequest(
        expiration = System.currentTimeMillis
      )

      override val e: RLPEncodeable = RLPList(
        p.expiration
      )
    },
    new RLPFixture[Payload.ENRResponse] {
      val (publicKey, privateKey) = sigalg.newKeyPair
      val node: Node = Node(
        id = publicKey,
        address = Node.Address(localhost, 30000, 40000)
      )
      val enr: EthereumNodeRecord = EthereumNodeRecord.fromNode(node, privateKey, seq = 1).require

      override val p: ENRResponse = Payload.ENRResponse(
        requestHash = Hash(randomBytes(32)),
        enr = enr
      )

      import EthereumNodeRecord.Keys

      override val e: RLPEncodeable = RLPList(
        p.requestHash,
        RLPList(
          p.enr.signature,
          p.enr.content.seq,
          Keys.id,
          p.enr.content.attrs(Keys.id),
          Keys.ip,
          p.enr.content.attrs(Keys.ip),
          Keys.secp256k1,
          p.enr.content.attrs(Keys.secp256k1),
          Keys.tcp,
          p.enr.content.attrs(Keys.tcp),
          Keys.udp,
          p.enr.content.attrs(Keys.udp)
        )
      )
    }
  )

  examples.foreach { example =>
    it should s"encode the example ${example.name}" in {
      example.testEncode
    }

    it should s"decode the example ${example.name}" in {
      example.testDecode
    }
  }
}
