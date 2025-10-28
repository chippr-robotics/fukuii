package com.chipprbots.ethereum.faucet.jsonrpc

import javax.net.ssl.SSLContext

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.util.ByteString

import cats.effect.IO

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import io.circe.syntax._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.client.RpcClient
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcError
import com.chipprbots.ethereum.security.SSLError
import com.chipprbots.ethereum.utils.Logger

class WalletRpcClient(node: Uri, timeout: Duration, getSSLContext: () => Either[SSLError, SSLContext])(implicit
    system: ActorSystem,
    ec: ExecutionContext
) extends RpcClient(node, timeout, getSSLContext)
    with Logger {
  import com.chipprbots.ethereum.jsonrpc.client.CommonJsonCodecs._

  def getNonce(address: Address): IO[Either[RpcError, BigInt]] =
    doRequest[BigInt]("eth_getTransactionCount", List(address.asJson, "latest".asJson))

  def sendTransaction(rawTx: ByteString): IO[Either[RpcError, ByteString]] =
    doRequest[ByteString]("eth_sendRawTransaction", List(rawTx.asJson))
}
