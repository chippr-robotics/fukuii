package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayOutputStream

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber

/** Archives of format version 2 built by hand, for the codec's and the importer's specs. */
object CheckpointV2TestKit:

  /** `checkpoint`, relinked to the window below it: the `min(256, N)` headers below it, child first, each the parent of
    * the one before. Their contents are `checkpoint`'s but for the number and the parent.
    */
  def withWindow(checkpoint: BlockHeader): (BlockHeader, Seq[BlockHeader]) =
    val n = checkpoint.number.value
    val depth = CheckpointArchive.windowHeaderCount(n)
    val lowest = n - depth
    // lowest first, so each can name its parent
    val upward = (0 until depth).foldLeft(Vector.empty[BlockHeader]) { (acc, i) =>
      acc :+ checkpoint.copy(
        number = BlockNumber(lowest + i),
        parentHash = acc.lastOption.map(_.hash).getOrElse(BlockHash(ByteString(new Array[Byte](32))))
      )
    }
    val relinked = upward.lastOption.fold(checkpoint)(parent => checkpoint.copy(parentHash = parent.hash))
    (relinked, upward.reverse)

  /** The genesis hash a test archive names: the window's last header when it reaches block 0, else a fixed one. */
  def genesisOf(window: Seq[BlockHeader]): ByteString =
    window.lastOption.filter(_.number.value == 0).map(_.hash.value).getOrElse(ByteString(Array.fill[Byte](32)(0x11)))

  /** An archive of `checkpoint` (relinked to a window built by [[withWindow]]) holding `nodes` and `codes` as given:
    * the codes are sorted by hash here, as the format requires.
    */
  def archive(
      chainId: Long,
      checkpoint: BlockHeader,
      totalDifficulty: BigInt,
      nodes: Seq[Array[Byte]],
      codes: Seq[Array[Byte]]
  ): (CheckpointArchive.Header, Array[Byte]) =
    val (relinked, window) = withWindow(checkpoint)
    val header = CheckpointArchive.Header(
      chainId = chainId,
      genesisHash = genesisOf(window),
      tdExact = true,
      blockHeader = relinked,
      totalDifficulty = totalDifficulty
    )
    val out = new ByteArrayOutputStream()
    val w = new CheckpointArchive.Writer(out)
    w.writeHeader(header)
    window.foreach(w.writeWindowHeader)
    nodes.foreach(w.writeNode)
    sortedCodes(codes).foreach(w.writeCode)
    w.finish()
    (header, out.toByteArray)

  def sortedCodes(codes: Seq[Array[Byte]]): Seq[Array[Byte]] =
    codes
      .map(c => ByteString(com.chipprbots.ethereum.crypto.kec256(c)) -> c)
      .sortBy(_._1)(using CheckpointArchive.unsignedOrder)
      .map(_._2)
