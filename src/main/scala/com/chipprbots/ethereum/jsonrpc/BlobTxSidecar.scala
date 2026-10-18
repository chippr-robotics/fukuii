package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import ethereum.ckzg4844.CKZG4844JNI
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.KzgCellProofs
import com.chipprbots.ethereum.domain.BlobVersionedHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The blobs, KZG commitments and proofs that travel with an EIP-4844 transaction outside its signed payload.
  *
  * `version` is go-ethereum's `BlobSidecarVersion`: 0 carries one blob proof per blob (EIP-4844 network wrapper `[tx,
  * blobs, commitments, proofs]`), 1 carries `CELLS_PER_EXT_BLOB` cell proofs per blob (EIP-7594 / Osaka wrapper `[tx,
  * 1, blobs, commitments, cell_proofs]`).
  */
final case class BlobTxSidecar(
    version: Byte,
    blobs: List[ByteString],
    commitments: List[ByteString],
    proofs: List[ByteString]
):
  /** The transaction in network form (EIP-2718 type byte, then the wrapper list), as `eth_sendRawTransaction` and the
    * pool's sidecar store expect it and as go-ethereum's `MarshalBinary` emits a blob tx that carries a sidecar.
    */
  def networkEncoding(stx: SignedTransaction): ByteString =
    stx.toRLPEncodable match
      case PrefixedRLPEncodable(prefix, payload) =>
        def list(items: List[ByteString]) = RLPList(items.map(b => RLPValue(b.toArray[Byte]))*)
        val versionItem = if version == BlobTxSidecar.Version1 then List(RLPValue(Array[Byte](1))) else Nil
        val wrapper = RLPList((payload :: versionItem ::: List(list(blobs), list(commitments), list(proofs)))*)
        ByteString(prefix) ++ ByteString(rlp.encode(wrapper))
      case other =>
        throw new IllegalArgumentException(s"blob sidecar attached to a non-typed transaction: $other")

object BlobTxSidecar:
  // go-ethereum core/types/tx_blob.go BlobSidecarVersion0 / BlobSidecarVersion1
  val Version0: Byte = 0
  val Version1: Byte = 1

  /** go-ethereum params.BlobTxMaxBlobs (EIP-7594 per-transaction blob limit). */
  val MaxBlobsPerTx: Int = 6

  /** go-ethereum kzg4844.CellProofsPerBlob = CELLS_PER_EXT_BLOB. */
  val CellProofsPerBlob: Int = 128

  /** go-ethereum `currentBlobSidecarVersion` (internal/ethapi/api.go:1742-1748): version 1 once Osaka is active at the
    * head, version 0 before.
    */
  def versionAt(head: BlockHeader)(using config: BlockchainConfig): Byte =
    if config.isOsakaTimestamp(head.unixTimestamp) then Version1 else Version0

  /** EIP-4844 `kzg_to_versioned_hash`: `0x01 || sha256(commitment)[1:]` (go-ethereum kzg4844.CalcBlobHashV1). */
  def versionedHash(commitment: ByteString): BlobVersionedHash =
    BlobVersionedHash(ByteString(0x01.toByte) ++ crypto.sha256(commitment).drop(1))

  /** The sidecar the arguments carry once [[complete]] has run (go-ethereum `ToTransaction`, which picks version 1 when
    * the proof count is `CellProofsPerBlob` per blob), or `None` when no blobs were given.
    */
  def fromArgs(args: TxArgs): Option[BlobTxSidecar] =
    for
      blobs <- args.blobs
      commitments <- args.commitments
      proofs <- args.proofs
    yield
      val version = if proofs.size == blobs.size * CellProofsPerBlob then Version1 else Version0
      BlobTxSidecar(version, blobs, commitments, proofs)

  /** go-ethereum `TransactionArgs.setBlobTxSidecar` (internal/ethapi/transaction_args.go:287-390), minus its
    * `blobSidecarAllowed` gate, which the calling endpoint applies.
    *
    * With `blobs` present: commitments and proofs must be both given or both absent; their counts must match the blobs
    * (proofs: one per blob for version 0, `CellProofsPerBlob` per blob for version 1; a version-1 request that carries
    * one proof per blob is the legacy format and is recomputed). Absent commitments and proofs are computed with KZG;
    * given ones are verified. The versioned hashes are derived from the commitments and, when given, must match.
    */
  def complete(args: TxArgs, version: Byte): Either[TxArgsError, TxArgs] =
    args.blobs match
      case None => Right(args)
      case Some(blobs) =>
        val n = blobs.size
        val proofLen = if version == Version1 then n * CellProofsPerBlob else n
        for
          _ <- (args.commitments, args.proofs) match
            case (None, Some(_)) => Left(TxArgsError("blob proofs provided while commitments were not"))
            case (Some(_), None) => Left(TxArgsError("blob commitments provided while proofs were not"))
            case _               => Right(())
          _ <- args.blobVersionedHashes match
            case Some(hs) if hs.size != n =>
              Left(TxArgsError(s"number of blobs and hashes mismatch (have=${hs.size}, want=$n)"))
            case _ => Right(())
          _ <- args.commitments match
            case Some(cs) if cs.size != n =>
              Left(TxArgsError(s"number of blobs and commitments mismatch (have=${cs.size}, want=$n)"))
            case _ => Right(())
          // geth: a proof count of one per blob under version 1 is the legacy format; drop and recompute
          supplied <- args.proofs match
            case Some(ps) if ps.size != proofLen =>
              if ps.size != n then
                Left(TxArgsError(s"number of blobs and proofs mismatch (have=${ps.size}, want=$proofLen)"))
              else Right(None)
            case Some(ps) => Right(args.commitments.map(_ -> ps))
            case None     => Right(None)
          (commitments, proofs) <- supplied match
            case None                    => compute(blobs, version)
            case Some((commitments, ps)) => verify(blobs, commitments, ps, version).map(_ => (commitments, ps))
          hashes = commitments.map(versionedHash)
          _ <- args.blobVersionedHashes match
            case Some(have) =>
              have.zip(hashes).collectFirst { case (h, want) if h.value != want.value => (h, want) } match
                case Some((h, want)) =>
                  Left(
                    TxArgsError(
                      s"blob hash verification failed (have=${hex(h.value)}, want=${hex(want.value)})"
                    )
                  )
                case None => Right(())
            case None => Right(())
        yield args.copy(
          commitments = Some(commitments),
          proofs = Some(proofs),
          blobVersionedHashes = args.blobVersionedHashes.orElse(Some(hashes))
        )

  private def hex(b: ByteString): String = "0x" + Hex.toHexString(b.toArray[Byte])

  /** Run a c-kzg-4844 call. Besides its own exceptions (bad input, trusted setup not loaded) the JNI throws
    * UnsatisfiedLinkError when the node skipped loading the native library (Fukuii.scala does so when the trusted setup
    * fails pre-validation); `Try` would let that LinkageError escape as an internal error, so it is caught here and
    * reported as geth reports a KZG failure.
    */
  private def kzg[A](f: => A): Either[Throwable, A] =
    try Right(f)
    catch
      case e: Exception    => Left(e)
      case e: LinkageError => Left(e)

  private def cause(e: Throwable): String = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)

  private def compute(
      blobs: List[ByteString],
      version: Byte
  ): Either[TxArgsError, (List[ByteString], List[ByteString])] =
    blobs.zipWithIndex.foldLeft[Either[TxArgsError, (List[ByteString], List[ByteString])]](Right((Nil, Nil))) {
      case (acc, (blob, i)) =>
        acc.flatMap { case (cs, ps) =>
          val raw = blob.toArray[Byte]
          for
            c <- kzg(CKZG4844JNI.blobToKzgCommitment(raw)).left
              .map(e => TxArgsError(s"blobs[$i]: error computing commitment: ${cause(e)}"))
            p <- kzg {
              if version == Version1 then KzgCellProofs.computeCellsAndKzgProofs(raw)._2.toList.map(ByteString(_))
              else List(ByteString(CKZG4844JNI.computeBlobKzgProof(raw, c)))
            }.left.map(e => TxArgsError(s"blobs[$i]: error computing proof: ${cause(e)}"))
          yield (cs :+ ByteString(c), ps ++ p)
        }
    }

  private def verify(
      blobs: List[ByteString],
      commitments: List[ByteString],
      proofs: List[ByteString],
      version: Byte
  ): Either[TxArgsError, Unit] =
    val verified: Either[Throwable, Boolean] = kzg {
      if version == Version0 then
        blobs.indices.forall { i =>
          CKZG4844JNI.verifyBlobKzgProof(blobs(i).toArray[Byte], commitments(i).toArray[Byte], proofs(i).toArray[Byte])
        }
      else
        // go-ethereum kzg4844.VerifyCellProofs: recompute every blob's cells, then batch-verify each cell against its
        // blob's commitment and the caller's cell proof.
        val cells = blobs.map(b => KzgCellProofs.computeCellsAndKzgProofs(b.toArray)._1)
        val flatCommitments =
          blobs.indices.flatMap(i => Seq.fill(CellProofsPerBlob)(commitments(i).toArray[Byte])).toArray.flatten
        val indices = blobs.indices.flatMap(_ => (0 until CellProofsPerBlob).map(_.toLong)).toArray
        val flatCells = cells.flatMap(_.toSeq).toArray.flatten
        val flatProofs = proofs.map(_.toArray[Byte]).toArray.flatten
        CKZG4844JNI.verifyCellKzgProofBatch(flatCommitments, indices, flatCells, flatProofs)
    }
    verified match
      case Right(true)  => Right(())
      case Right(false) => Left(TxArgsError("failed to verify blob proof: invalid proof"))
      case Left(e)      => Left(TxArgsError(s"failed to verify blob proof: ${cause(e)}"))
