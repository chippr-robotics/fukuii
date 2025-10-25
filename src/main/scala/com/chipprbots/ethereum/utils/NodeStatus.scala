package com.chipprbots.ethereum.utils

import java.net.InetSocketAddress

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import com.chipprbots.ethereum.network._

sealed trait ServerStatus
object ServerStatus {
  case object NotListening extends ServerStatus
  case class Listening(address: InetSocketAddress) extends ServerStatus
}

case class NodeStatus(key: AsymmetricCipherKeyPair, serverStatus: ServerStatus, discoveryStatus: ServerStatus) {

  val nodeId: Array[Byte] = key.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId
}
