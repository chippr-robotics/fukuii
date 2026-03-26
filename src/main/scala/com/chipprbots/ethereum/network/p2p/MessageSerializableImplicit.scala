package com.chipprbots.ethereum.network.p2p

/** Helper class
  */
// msg: T provides typed access used by all subclasses (import msg._, msg.requestId, etc.).
// underlyingMsg on MessageSerializable returns untyped Message — not a substitute.
abstract class MessageSerializableImplicit[T <: Message](val msg: T) extends MessageSerializable {

  override def equals(that: Any): Boolean = that match {
    case that: MessageSerializableImplicit[_] => that.msg.equals(msg)
    case _                                    => false
  }

  override def hashCode(): Int = msg.hashCode()

  override def toShortString: String = msg.toShortString
}
