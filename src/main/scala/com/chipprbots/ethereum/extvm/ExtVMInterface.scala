package com.chipprbots.ethereum.extvm

import java.nio.ByteOrder

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Framing
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.scaladsl.Tcp
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.VmConfig
import com.chipprbots.ethereum.vm._

/** HIBERNATED: External VM features are currently in hibernation. This component is experimental and not core to
  * fukuii's functioning. Use vm.mode = "internal" in configuration (default setting).
  */
class ExtVMInterface(externaVmConfig: VmConfig.ExternalConfig, blockchainConfig: BlockchainConfig, testMode: Boolean)(
    implicit system: ActorSystem
) extends VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage] {
  private var vmClient: Option[VMClient] = None

  initConnection()

  private def initConnection(): Unit = {
    close()

    val connection = Tcp().outgoingConnection(externaVmConfig.host, externaVmConfig.port)

    val (connOut, connIn) = Source
      .queue[ByteString](QueueBufferSize, OverflowStrategy.dropTail)
      .via(connection)
      .via(Framing.lengthField(LengthPrefixSize, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN))
      .map(_.drop(4))
      .toMat(Sink.queue[ByteString]())(Keep.both)
      .run()

    val client = new VMClient(externaVmConfig, new MessageHandler(connIn, connOut), testMode)
    client.sendHello(ApiVersionProvider.version, blockchainConfig)
    // TODO: await hello response, check version

    vmClient = Some(client)
  }

  @tailrec
  final override def run(context: PC): PR = {
    if (vmClient.isEmpty) initConnection()

    val client = vmClient.getOrElse(throw new IllegalStateException("VM client not initialized"))
    Try(client.run(context)) match {
      case Success(res) => res
      case Failure(ex) =>
        ex.printStackTrace()
        initConnection()
        run(context)
    }
  }

  def close(): Unit = {
    vmClient.foreach(_.close())
    vmClient = None
  }

}
