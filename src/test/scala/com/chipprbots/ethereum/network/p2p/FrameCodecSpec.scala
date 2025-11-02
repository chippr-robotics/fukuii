package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.rlpx.Frame
import com.chipprbots.ethereum.network.rlpx.FrameCodec
import com.chipprbots.ethereum.network.rlpx.Header
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.rawDecode

class FrameCodecSpec extends AnyFlatSpec with Matchers {

  import DummyMsg._

  it should "send message and receive a response" in new SecureChannelSetup {
    val frameCodec = new FrameCodec(secrets)
    val remoteFrameCodec = new FrameCodec(remoteSecrets)

    val sampleMessage: DummyMsg = DummyMsg(2310, ByteString("Sample Message"))
    val sampleMessageEncoded: ByteString = sampleMessage.toBytes
    val sampleMessageFrame: Frame = Frame(
      Header(sampleMessageEncoded.length, 0, None, Some(sampleMessageEncoded.length)),
      sampleMessage.code,
      sampleMessageEncoded
    )
    val sampleMessageData: ByteString = remoteFrameCodec.writeFrames(Seq(sampleMessageFrame))
    val sampleMessageReadFrames: Seq[Frame] = frameCodec.readFrames(sampleMessageData)
    val sampleMessageReadMessage: DummyMsg = sampleMessageReadFrames.head.payload.toArray[Byte].toSample

    sampleMessageReadMessage shouldBe sampleMessage
  }

  object DummyMsg {
    val code: Int = 2323

    implicit class DummyMsgEnc(val underlyingMsg: DummyMsg) extends MessageSerializable with RLPSerializable {
      override def code: Int = DummyMsg.code

      override def toRLPEncodable: RLPEncodeable = {
        import com.chipprbots.ethereum.rlp.RLPImplicits.{intEncDec, byteStringEncDec}
        RLPList(
          intEncDec.encode(underlyingMsg.aField),
          byteStringEncDec.encode(underlyingMsg.anotherField)
        )
      }
      override def toShortString: String = underlyingMsg.toShortString
    }

    implicit class DummyMsgDec(val bytes: Array[Byte]) {
      def toSample: DummyMsg = rawDecode(bytes) match {
        case RLPList(aField, anotherField) =>
          DummyMsg(aField.decodeAs[Int]("aField"), ByteString(anotherField.decodeAs[Array[Byte]]("anotherField")))
        case _ => throw new RuntimeException("Cannot decode Status")
      }
    }
  }

  case class DummyMsg(aField: Int, anotherField: ByteString) extends Message {
    override def code: Int = DummyMsg.code
    override def toShortString: String = toString
  }

}
