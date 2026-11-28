package com.chipprbots.ethereum

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.tls.OutboundTls
import com.chipprbots.ethereum.utils.Logger

/** A facility to
  *   - check the download location for a minimum amount of free space
  *   - download a zip from a URL and generate SHA-512 checksum
  *   - check the checksum
  *   - clean files out of given location
  *   - unzip to a given location
  */
object BootstrapDownload extends Logger:

  val bufferSize: Int = 4 * 1024
  val leveldbFolderName = "leveldb"

  private def assertAndLog(cond: Boolean, msg: String): Unit =
    if !cond then log.info(msg)
    assert(cond, msg)

  def cleanOutFolder(pathToDownloadTo: Path): Unit =
    val leveldbFolder = pathToDownloadTo.toFile
    assertAndLog(leveldbFolder.isDirectory, s"${pathToDownloadTo} must be a folder.")
    assertAndLog(
      leveldbFolder.getName == leveldbFolderName,
      s"${pathToDownloadTo} must end in a folder named $leveldbFolderName"
    )
    leveldbFolder.listFiles(pathname => !pathname.getName.endsWith(".zip")).foreach(_.delete())

  /** Downloads `urlToDownloadFrom` (`https` only) on `context`, the client context of
    * `fukuii.bootstrap-download.tls-profile` (spec 030 FR-044), and returns the file's SHA-512. A URL that is not
    * `https`, or a redirect to one, is refused (FR-043).
    */
  def downloadFile(urlToDownloadFrom: String, outFile: File, context: SSLContext = SSLContext.getDefault): String =

    val sha512 = MessageDigest.getInstance("SHA-512")
    val in = OutboundTls
      .openHttpsStream(urlToDownloadFrom, context, timeout = java.time.Duration.ofMinutes(2))
      .fold(why => throw new IllegalArgumentException(s"Bootstrap download refused: $why"), identity)
    val dis = new DigestInputStream(in, sha512)

    try
      val out = new FileOutputStream(outFile)
      try
        val buffer = new Array[Byte](bufferSize)

        Iterator.continually(dis.read(buffer)).takeWhile(_ != -1).foreach(out.write(buffer, 0, _))
      finally out.close()
      Hex.toHexString(sha512.digest)

    finally dis.close()

  def unzip(zipFile: File, destination: Path): Unit =

    val in = new FileInputStream(zipFile)
    try
      val zis = new ZipInputStream(in)
      try
        Iterator.continually(zis.getNextEntry).takeWhile(_ != null).foreach { file =>
          if !file.isDirectory then
            val outPath = destination.resolve(file.getName)
            val outPathParent = outPath.getParent
            if !outPathParent.toFile.exists() then outPathParent.toFile.mkdirs()

            val outFile = outPath.toFile
            val out = new FileOutputStream(outFile)
            try
              val buffer = new Array[Byte](bufferSize)
              Iterator.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(out.write(buffer, 0, _))
            finally out.close()
        }
      finally zis.close()
    finally in.close()

  def deleteDownloadedFile(downloadedFile: File): Unit =
    if downloadedFile.delete() then log.info(s"Downloaded file $downloadedFile successfully deleted")
    else log.info(s"Failed to delete downloaded file $downloadedFile")

  // scalastyle:off method.length
  def main(args: Array[String]): Unit =
    // download a zip file from a url.

    assertAndLog(
      args.length == 4,
      "Provide the url to download from, " +
        " expected hash of the downloaded file, " +
        " the minimum required free disk space in giga bytes" +
        " and the path to extract the file to"
    )

    val urlToDownloadFrom = URI.create(args(0)).toURL()
    val expectedHash = args(1)
    val minimumExpectedDiskSpace = args(2)
    val pathToDownloadTo = Paths.get(args(3))

    val bytesInOneGigaByte = 1024L * 1024L * 1024L
    val minimumExpectedDiskSpaceInBytes = minimumExpectedDiskSpace.toLong * bytesInOneGigaByte

    val urlToDownloadFromAsFile = new File(urlToDownloadFrom.getFile)
    val pathToDownloadToAsFile = pathToDownloadTo.toFile
    val downloadedFile = new File(pathToDownloadToAsFile, urlToDownloadFromAsFile.getName)

    log.info(s"Running Bootstrap download ... ")
    log.info(s"Expected Minimum disk space is $minimumExpectedDiskSpace GB")
    log.info(s"Download path is $urlToDownloadFrom")
    log.info(s"Path to download to is $pathToDownloadTo")

    if !pathToDownloadToAsFile.exists() then pathToDownloadToAsFile.mkdirs()

    assertAndLog(pathToDownloadToAsFile.isDirectory, s"$pathToDownloadToAsFile must be a folder.")
    assertAndLog(
      pathToDownloadToAsFile.getUsableSpace() >= minimumExpectedDiskSpaceInBytes,
      s"There is not enough free space ($minimumExpectedDiskSpace GB) to download and expand to $pathToDownloadTo "
    )

    log.info(s"Free space check ok, starting download! (this could take some time)")
    val context = OutboundTls
      .forSite(
        com.chipprbots.ethereum.utils.Config.config,
        "bootstrap-download.tls-profile",
        new java.security.SecureRandom()
      )
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val hash = downloadFile(args(0), downloadedFile, context)

    log.info(s"Download complete, checking hash against $expectedHash ...")
    assertAndLog(hash == expectedHash, s"The zip file hash $hash did NOT match the expected hash $expectedHash")

    log.info(s"Hash OK, clean out folder...")
    cleanOutFolder(pathToDownloadTo)

    log.info(s"Unzip file ${pathToDownloadTo} ${downloadedFile}...")
    unzip(downloadedFile, pathToDownloadTo)

    deleteDownloadedFile(downloadedFile)

    log.info(s"Bootstrap download successful.")
