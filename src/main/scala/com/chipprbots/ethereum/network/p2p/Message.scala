package com.chipprbots.ethereum.network.p2p

import cats.implicits._

import com.chipprbots.ethereum.utils.Logger

trait Message {
  def code: Int
  def toShortString: String
}

trait MessageSerializable extends Message {
  def toBytes: Array[Byte]
  def underlyingMsg: Message
}

@FunctionalInterface
trait MessageDecoder extends Logger { self =>
  import MessageDecoder._

  def fromBytes(`type`: Int, payload: Array[Byte]): Either[DecodingError, Message]

  def orElse(otherMessageDecoder: MessageDecoder): MessageDecoder = new MessageDecoder {
    override def fromBytes(`type`: Int, payload: Array[Byte]): Either[DecodingError, Message] =
      self.fromBytes(`type`, payload).leftFlatMap { err =>
        log.debug(err.getLocalizedMessage())
        otherMessageDecoder.fromBytes(`type`, payload)
      }
  }
}

object MessageDecoder {
  // Sealed trait hierarchy for decoding errors, providing type-safe error handling
  sealed trait DecodingError extends Throwable {
    def message: String
    override def getMessage: String = message
  }
  
  // Decompression-related errors (can be tolerated in some cases)
  final case class DecompressionFailure(message: String, cause: Throwable) extends DecodingError {
    override def getCause: Throwable = cause
  }
  
  // Other decoding errors (should close connection)
  final case class MalformedMessageError(message: String, cause: Option[Throwable] = None) extends DecodingError {
    override def getCause: Throwable = cause.orNull
  }
  
  final case class UnknownMessageTypeError(messageType: Int, message: String) extends DecodingError
  
  // Helper to determine if an error is a decompression failure
  def isDecompressionFailure(error: DecodingError): Boolean = error match {
    case _: DecompressionFailure => true
    case _ => false
  }
}
