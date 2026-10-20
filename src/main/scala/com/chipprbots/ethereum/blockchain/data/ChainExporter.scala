package com.chipprbots.ethereum.blockchain.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.utils.Logger

/** Writes a range of the canonical chain as a [[ChainFile]] — go-ethereum's `BlockChain.ExportN` (core/blockchain.go).
  *
  * Fails, and leaves no file behind, if any block of the range is missing, its stored body does not match its header,
  * or the range stops being one chain while it is read (a reorg). The old exporter skipped missing blocks and still
  * reported success.
  *
  * The file is written to a temporary sibling and renamed into place on success, so `file` either does not exist or
  * holds the whole range. Like geth's `admin_exportChain`, an existing `file` is never overwritten.
  */
object ChainExporter extends Logger:

  final case class ExportSummary(first: BigInt, last: BigInt, blocks: Long, bytes: Long)

  private val ProgressIntervalNanos: Long = 8_000_000_000L // geth's statsReportLimit

  def exportChain(
      reader: BlockchainReader,
      file: Path,
      first: BigInt,
      last: BigInt
  ): Either[String, ExportSummary] =
    if first < 0 then Left(s"export failed: first ($first) is negative")
    else if first > last then Left(s"export failed: first ($first) is greater than last ($last)")
    else if Files.exists(file) then Left(s"export failed: $file already exists; refusing to overwrite it")
    else
      val target = file.toAbsolutePath
      Option(target.getParent).foreach(Files.createDirectories(_))
      val tmp = target.resolveSibling(s".${target.getFileName}.${ProcessHandle.current().pid()}.partial")
      log.info(s"Exporting blocks $first..$last (${last - first + 1}) to $target")
      val result =
        try
          val out = ChainFile.wrapOutput(
            target,
            Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
          )
          val written =
            try writeRange(reader, out, first, last)
            finally out.close()
          written.flatMap { blocks =>
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            Right(ExportSummary(first, last, blocks, Files.size(target)))
          }
        catch case NonFatal(e) => Left(s"export failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
      if result.isLeft then Files.deleteIfExists(tmp)
      result.foreach(s => log.info(s"Exported ${s.blocks} blocks ($first..$last, ${s.bytes} bytes) to $target"))
      result.left.foreach(err => log.error(err))
      result

  private def writeRange(
      reader: BlockchainReader,
      out: java.io.OutputStream,
      first: BigInt,
      last: BigInt
  ): Either[String, Long] =
    val start = System.nanoTime()
    var reported = start
    var parentHash: Option[BlockHash] = None
    var number = first
    var count = 0L
    var failure: Option[String] = None
    while failure.isEmpty && number <= last do
      val block = reader.getBlockHeaderByNumber(number).flatMap(header => reader.getBlockByHash(header.hash))
      block match
        case None =>
          val best = reader.getBestBlockNumber
          val why =
            if number > best then s"beyond the local head $best"
            else if reader.getBlockHeaderByNumber(number).isEmpty then "no canonical header at that height"
            else "the body is not stored (e.g. below a SNAP-sync pivot)"
          failure = Some(s"export failed on #$number: not found ($why)")
        case Some(b) if parentHash.exists(_ != b.header.parentHash) =>
          failure = Some(s"export failed on #$number: chain reorg during export")
        case Some(b) =>
          StdBlockValidator.validateHeaderAndBody(b.header, b.body) match
            case Left(err) =>
              // The stored body does not match its header (transactions root, ommers hash, withdrawals, blob gas):
              // the block would encode under the right hash, but no client could import it. Refuse to ship it.
              failure = Some(
                s"export failed on #$number: the stored body does not match its header ($err); the local " +
                  "database holds an incomplete block"
              )
            case Right(_) =>
              out.write(ChainFile.encode(b))
              parentHash = Some(b.header.hash)
              count += 1
              number += 1
              val now = System.nanoTime()
              if now - reported >= ProgressIntervalNanos then
                log.info(s"Exporting blocks: exported=$count of ${last - first + 1}, at #${number - 1}")
                reported = now
    failure.toLeft(count)
