package com.chipprbots.ethereum.crypto

import ethereum.ckzg4844.CKZG4844JNI

/** EIP-7594 / PeerDAS KZG cell-proof operations.
  *
  * Wraps CKZG4844JNI.computeCellsAndKzgProofs, splitting the JNI's flat byte arrays into per-cell slices. Requires the
  * KZG trusted setup to be loaded before any call.
  *
  * Constants mirror the c-kzg-4844 spec: CELLS_PER_EXT_BLOB=128 cells per blob, each cell 2048 bytes, each proof a
  * 48-byte G1 point.
  */
object KzgCellProofs:
  val CELLS_PER_EXT_BLOB: Int = CKZG4844JNI.CELLS_PER_EXT_BLOB // 128
  val BYTES_PER_CELL: Int = CKZG4844JNI.BYTES_PER_CELL // 2048
  val BYTES_PER_PROOF: Int = CKZG4844JNI.BYTES_PER_PROOF // 48

  /** Compute EIP-7594 cell proofs for a single blob.
    *
    * @param blob
    *   131072-byte blob (4096 BLS field elements).
    * @return
    *   (cells, proofs) — each array has CELLS_PER_EXT_BLOB (128) elements; cells(i) is BYTES_PER_CELL (2048) bytes,
    *   proofs(i) is BYTES_PER_PROOF (48) bytes.
    * @throws ethereum.ckzg4844.CKZGException
    *   if the trusted setup is not loaded or the blob is malformed.
    */
  def computeCellsAndKzgProofs(blob: Array[Byte]): (Array[Array[Byte]], Array[Array[Byte]]) =
    val result = CKZG4844JNI.computeCellsAndKzgProofs(blob)
    val flatCells = result.getCells()
    val flatProofs = result.getProofs()
    val cells = flatCells.grouped(BYTES_PER_CELL).toArray
    val proofs = flatProofs.grouped(BYTES_PER_PROOF).toArray
    (cells, proofs)

  /** go-ethereum `kzg4844.VerifyBlobProof`: one EIP-4844 blob proof (sidecar version 0) against its blob and
    * commitment.
    *
    * @throws ethereum.ckzg4844.CKZGException
    *   if the trusted setup is not loaded or an input is malformed.
    */
  def verifyBlobProof(blob: Array[Byte], commitment: Array[Byte], proof: Array[Byte]): Boolean =
    CKZG4844JNI.verifyBlobKzgProof(blob, commitment, proof)

  /** go-ethereum `kzg4844.VerifyCellProofs` (sidecar version 1): recompute every blob's cells, then batch-verify each
    * cell against its blob's commitment and the given cell proof (`CELLS_PER_EXT_BLOB` proofs per blob, in blob order).
    *
    * @throws ethereum.ckzg4844.CKZGException
    *   if the trusted setup is not loaded or an input is malformed.
    */
  def verifyCellProofs(blobs: Seq[Array[Byte]], commitments: Seq[Array[Byte]], proofs: Seq[Array[Byte]]): Boolean =
    val cells = blobs.map(b => computeCellsAndKzgProofs(b)._1)
    val flatCommitments = blobs.indices.flatMap(i => Seq.fill(CELLS_PER_EXT_BLOB)(commitments(i))).toArray.flatten
    val indices = blobs.indices.flatMap(_ => (0 until CELLS_PER_EXT_BLOB).map(_.toLong)).toArray
    val flatCells = cells.flatMap(_.toSeq).toArray.flatten
    val flatProofs = proofs.toArray.flatten
    CKZG4844JNI.verifyCellKzgProofBatch(flatCommitments, indices, flatCells, flatProofs)
