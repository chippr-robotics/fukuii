package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.HttpsConnectionContext
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.utils.Logger

/** Pushes new PoW work packages to configured HTTP endpoints.
  *
  * core-geth reference: consensus/ethash/sealer.go notifyWork() (lines 407-447) Format: POST JSON array ["0xSealhash",
  * "0xSeed", "0xTarget", "0xBlockNumber"] Async: fire-and-forget; failures are logged, never fatal to the mining loop
  */
object WorkNotifier extends Logger:

  /** Work package fields match core-geth sealer.go remote work array: work[0] = sealhash (keccak256 of header without
    * nonce) work[1] = dagSeed (epoch identifier) work[2] = target (2^256 / difficulty, big-endian bytes) work[3] =
    * blockNumber (hex-encoded uint64)
    */
  final case class WorkPackage(
      powHeaderHash: ByteString,
      dagSeed: ByteString,
      target: ByteString,
      blockNumber: BigInt
  )

  /** Posts the work package to every configured URL. Each POST is independent; one failure does not affect others. An
    * `https` URL is reached on `https`, the client context of `fukuii.mining.notify-tls-profile` (spec 030 FR-044).
    */
  def notify(urls: Seq[String], work: WorkPackage, https: HttpsConnectionContext)(implicit system: ActorSystem): Unit =
    if urls.nonEmpty then
      implicit val ec: ExecutionContext = system.dispatcher
      urls.foreach { url =>
        send(url, work, https).onComplete {
          case Success(status) => log.debug("Work notification sent to {} (HTTP {})", url, status)
          case Failure(ex)     => log.warn("Work notification to {} failed: {}", url, ex.getMessage)
        }
      }

  /** One POST of `work` to `url`: the HTTP status it was answered with, or the failure. */
  def send(url: String, work: WorkPackage, https: HttpsConnectionContext)(implicit
      system: ActorSystem
  ): Future[Int] =
    implicit val ec: ExecutionContext = system.dispatcher
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = url,
      entity = HttpEntity(ContentTypes.`application/json`, buildJsonBody(work))
    )
    Http().singleRequest(request, https).map { response =>
      response.discardEntityBytes()
      response.status.intValue()
    }

  /** Builds the JSON array body matching core-geth's GetWork wire format. All values are 0x-prefixed hex strings.
    */
  private def buildJsonBody(work: WorkPackage): String =
    val sealhash = "0x" + Hex.toHexString(work.powHeaderHash.toArray)
    val seed = "0x" + Hex.toHexString(work.dagSeed.toArray)
    val target = "0x" + Hex.toHexString(work.target.toArray)
    val blockNum = "0x" + work.blockNumber.toString(16)
    s"""["$sealhash","$seed","$target","$blockNum"]"""
