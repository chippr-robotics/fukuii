package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration

import org.slf4j.LoggerFactory

/** HTTP fetcher for `.checkpoint` archives. Writes to `${target}.tmp` with a file lock so concurrent fukuii instances
  * on the same datadir can't duel. Supports HTTP `Range` requests for resume after a JVM restart partway through a
  * multi-GiB download.
  *
  * PR-2 in the checkpoint sync series. The output path is fed to [[CheckpointImporter]], which validates the CRC32
  * trailer; a corrupted download surfaces as a `BadFormat` from the importer rather than something this class enforces.
  *
  * Caller-controlled lifecycle: `download(url, target)` is synchronous; SyncController invokes it on a blocking
  * dispatcher.
  */
final class CheckpointDownloader(
    httpClient: HttpClient = CheckpointDownloader.defaultHttpClient,
    progressLogIntervalBytes: Long = CheckpointDownloader.DefaultProgressLogInterval
) {
  import CheckpointDownloader._
  private val log = LoggerFactory.getLogger(getClass)

  /** Download from `url` to `target`. If `${target}.tmp` exists, attempt to resume via `Range` header. If `target`
    * already exists, returns `Right(())` immediately (caller's job to detect and skip).
    *
    * On any failure the `.tmp` is left in place so the next attempt can resume.
    */
  def download(url: String, target: Path): Either[DownloadError, Unit] = {
    if (Files.exists(target)) {
      log.info("[CHECKPOINT DOWNLOAD] target {} already exists; skipping fetch", target)
      return Right(())
    }
    val tmpPath = target.resolveSibling(target.getFileName.toString + ".tmp")
    Files.createDirectories(target.getParent)

    val tmpChannel =
      try
        FileChannel.open(
          tmpPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE
        )
      catch {
        case e: IOException => return Left(IoError(s"open tmp: ${e.getMessage}"))
      }

    var lockOpt: Option[FileLock] = None
    try {
      try lockOpt = Option(tmpChannel.tryLock())
      catch { case _: OverlappingFileLockException => () }
      if (lockOpt.isEmpty) {
        log.warn("[CHECKPOINT DOWNLOAD] another process holds {}; refusing to start", tmpPath)
        return Left(AlreadyDownloading)
      }

      val resumeFrom = tmpChannel.size()
      val totalBytes = doDownload(url, tmpChannel, resumeFrom) match {
        case Right(t) => t
        case Left(e)  => return Left(e)
      }

      // Best-effort flush — file lock release on close is enough but make data hits disk first.
      tmpChannel.force(true)

      try Files.move(tmpPath, target, StandardCopyOption.ATOMIC_MOVE)
      catch {
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(tmpPath, target, StandardCopyOption.REPLACE_EXISTING)
        case e: IOException => return Left(IoError(s"rename tmp -> final: ${e.getMessage}"))
      }
      log.info(
        "[CHECKPOINT DOWNLOAD] complete: {} bytes -> {}",
        totalBytes,
        target
      )
      Right(())
    } finally {
      lockOpt.foreach(l =>
        try l.release()
        catch { case _: Throwable => () }
      )
      try tmpChannel.close()
      catch { case _: Throwable => () }
    }
  }

  /** Perform the HTTP GET, appending to `tmpChannel`. Returns total bytes in the completed file. */
  private def doDownload(
      url: String,
      tmpChannel: FileChannel,
      resumeFrom: Long
  ): Either[DownloadError, Long] = {
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .timeout(Duration.ofMinutes(30))
      .GET()
    if (resumeFrom > 0) {
      log.info("[CHECKPOINT DOWNLOAD] resuming from byte {} for {}", resumeFrom, url)
      builder.header("Range", s"bytes=$resumeFrom-")
    } else {
      log.info("[CHECKPOINT DOWNLOAD] fetching {}", url)
    }
    val request = builder.build()

    val response =
      try httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      catch {
        case e: java.net.http.HttpConnectTimeoutException =>
          return Left(HttpError(0, s"connect timeout: ${e.getMessage}"))
        case e: java.net.http.HttpTimeoutException =>
          return Left(HttpError(0, s"timeout: ${e.getMessage}"))
        case e: IOException =>
          return Left(HttpError(0, s"io: ${e.getMessage}"))
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          return Left(HttpError(0, s"interrupted: ${e.getMessage}"))
      }

    val status = response.statusCode()
    val rangeOk = resumeFrom > 0 && status == 206
    val fullOk = resumeFrom == 0 && (status == 200 || status == 206)
    if (!rangeOk && !fullOk) {
      // 200 to a Range request means server ignored the range — start over from byte 0.
      if (resumeFrom > 0 && status == 200) {
        log.warn(
          "[CHECKPOINT DOWNLOAD] server ignored Range header (status 200); restarting download from scratch"
        )
        tmpChannel.truncate(0)
        tmpChannel.position(0)
        return readBodyInto(response.body(), tmpChannel, alreadyWritten = 0)
      }
      val errBody =
        try
          new String(response.body().readNBytes(1024), "UTF-8")
        catch { case _: Throwable => "" }
      return Left(HttpError(status, errBody))
    }

    tmpChannel.position(resumeFrom)
    readBodyInto(response.body(), tmpChannel, alreadyWritten = resumeFrom)
  }

  private def readBodyInto(
      body: java.io.InputStream,
      tmpChannel: FileChannel,
      alreadyWritten: Long
  ): Either[DownloadError, Long] = {
    val out: OutputStream = Channels.newOutputStream(tmpChannel)
    val buf = new Array[Byte](64 * 1024)
    var total = alreadyWritten
    var nextLogAt = alreadyWritten + progressLogIntervalBytes
    try {
      var n = body.read(buf)
      while (n >= 0) {
        if (n > 0) {
          out.write(buf, 0, n)
          total += n
          if (total >= nextLogAt) {
            log.info("[CHECKPOINT DOWNLOAD] {} MiB written", total / (1024 * 1024))
            nextLogAt = total + progressLogIntervalBytes
          }
        }
        n = body.read(buf)
      }
      Right(total)
    } catch {
      case e: IOException => Left(IoError(s"body read: ${e.getMessage}"))
    } finally {
      try out.flush()
      catch { case _: Throwable => () }
      try body.close()
      catch { case _: Throwable => () }
    }
  }
}

object CheckpointDownloader {
  val DefaultProgressLogInterval: Long = 100L * 1024 * 1024 // log every 100 MiB

  lazy val defaultHttpClient: HttpClient =
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(30))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()

  sealed trait DownloadError extends Product with Serializable
  final case class HttpError(status: Int, message: String) extends DownloadError
  final case class IoError(reason: String) extends DownloadError
  case object AlreadyDownloading extends DownloadError
}
