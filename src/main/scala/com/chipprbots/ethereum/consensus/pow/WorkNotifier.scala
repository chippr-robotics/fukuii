package com.chipprbots.ethereum.consensus.pow

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.{Duration => JDuration}

import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.Logger

/** Sends HTTP POST notifications to configured URLs when new mining work is available.
  *
  * Matches geth's pre-merge `remoteSealer.notifyWork()` behavior: fire-and-forget POST with 1s timeout. Failures are
  * logged but never block mining.
  *
  * @param notifyUrls
  *   HTTP URLs to POST work to
  * @param notifyFull
  *   If true, POST full block header JSON instead of work array
  */
class WorkNotifier(
    notifyUrls: Seq[String],
    notifyFull: Boolean
)(implicit ec: ExecutionContext)
    extends Logger {

  private val httpClient: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(JDuration.ofSeconds(1))
    .build()

  def hasTargets: Boolean = notifyUrls.nonEmpty

  /** Notify all configured URLs of new work.
    *
    * @param powHeaderHash
    *   the PoW header hash (keccak256 of header without nonce)
    * @param dagSeed
    *   the DAG seed hash for the epoch
    * @param target
    *   the target difficulty
    * @param blockNumber
    *   the block number being mined
    * @param header
    *   the full block header (used when notifyFull is true)
    */
  def notifyWork(
      powHeaderHash: ByteString,
      dagSeed: ByteString,
      target: ByteString,
      blockNumber: BigInt,
      header: Option[BlockHeader] = None
  ): Unit =
    if (notifyUrls.nonEmpty) {
      val json = if (notifyFull && header.isDefined) {
        buildFullHeaderJson(header.get, blockNumber)
      } else {
        buildWorkArrayJson(powHeaderHash, dagSeed, target, blockNumber)
      }

      notifyUrls.foreach { url =>
        Future {
          try {
            val request = HttpRequest
              .newBuilder()
              .uri(URI.create(url))
              .timeout(JDuration.ofSeconds(1))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 400) {
              log.warn(s"Work notification to $url returned status ${response.statusCode()}")
            }
          } catch {
            case ex: Exception =>
              log.warn(s"Work notification to $url failed: ${ex.getMessage}")
          }
        }.onComplete {
          case Failure(ex) => log.warn(s"Work notification future to $url failed: ${ex.getMessage}")
          case Success(_)  => // ok
        }
      }
    }

  private def toHexString(bs: ByteString): String =
    "0x" + bs.map(b => String.format("%02x", Byte.box(b))).mkString

  /** Builds the 4-element work array JSON matching geth's format: [powHeaderHash, dagSeed, target, blockNumber] */
  private def buildWorkArrayJson(
      powHeaderHash: ByteString,
      dagSeed: ByteString,
      target: ByteString,
      blockNumber: BigInt
  ): String = {
    val hashHex = toHexString(powHeaderHash)
    val seedHex = toHexString(dagSeed)
    val targetHex = toHexString(target)
    val blockHex = "0x" + blockNumber.toString(16)
    s"""["$hashHex","$seedHex","$targetHex","$blockHex"]"""
  }

  /** Builds full header JSON for notifyFull mode */
  private def buildFullHeaderJson(header: BlockHeader, blockNumber: BigInt): String = {
    val fields = Seq(
      s""""parentHash":"${toHexString(header.parentHash)}"""",
      s""""sha3Uncles":"${toHexString(header.ommersHash)}"""",
      s""""miner":"${toHexString(header.beneficiary)}"""",
      s""""stateRoot":"${toHexString(header.stateRoot)}"""",
      s""""transactionsRoot":"${toHexString(header.transactionsRoot)}"""",
      s""""receiptsRoot":"${toHexString(header.receiptsRoot)}"""",
      s""""logsBloom":"${toHexString(header.logsBloom)}"""",
      s""""difficulty":"0x${header.difficulty.toString(16)}"""",
      s""""number":"0x${blockNumber.toString(16)}"""",
      s""""gasLimit":"0x${header.gasLimit.toString(16)}"""",
      s""""gasUsed":"0x${header.gasUsed.toString(16)}"""",
      s""""timestamp":"0x${java.lang.Long.toHexString(header.unixTimestamp)}"""",
      s""""extraData":"${toHexString(header.extraData)}""""
    )
    "{" + fields.mkString(",") + "}"
  }
}
