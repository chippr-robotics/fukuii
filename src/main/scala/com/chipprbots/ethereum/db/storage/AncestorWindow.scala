package com.chipprbots.ethereum.db.storage

import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader

/** What a node must hold of the chain below a block before it executes the first block above it (spec 018 C5a part 2;
  * T204): the window a sync that began from a pivot hands off with.
  *
  *   - '''The headers of the 256 blocks below it, each found by the hash its child names.''' `BLOCKHASH` answers from a
  *     walk of parent hashes ([[com.chipprbots.ethereum.ledger.AncestorBlockHashes]]). Where the walk breaks it falls
  *     back to the index of blocks by number, and where that is empty it answers zero: the block's state root then
  *     differs from every other client's, and a valid block is refused. So the walk itself is what is checked, not the
  *     index.
  *   - '''On a proof-of-work chain, the bodies of the block and of the four below it, where their headers name
  *     ommers.''' The ommers of a block are checked against the headers of its six ancestors, and against the ommers
  *     the nearest five used ([[com.chipprbots.ethereum.consensus.pow.validators.StdOmmersValidator]]): a body whose
  *     header names none is not read (B-302, core-geth's `VerifyUncles`). For the first block above a pivot `P` those
  *     are `P` down to `P - 4`, the pivot's own body among them. After the merge a block has no ommers and no body is
  *     read.
  *
  * Both are bounded by the genesis: a block near it has fewer ancestors, and that is all of them.
  */
object AncestorWindow:

  /** How far back `BLOCKHASH` reaches. */
  val HashDepth: Int = 256

  /** How many ancestors a block's ommers are checked against. */
  val OmmerGenerations: Int = 6

  /** How many of them the check reads the bodies of, where they have ommers. */
  val OmmerBodies: Int = OmmerGenerations - 1

  /** Whether the check of ommers reads the body of this ancestor: where its header names ommers. */
  def bodyRead(header: BlockHeader): Boolean = header.ommersHash.value != BlockHeader.EmptyOmmers

  /** What is not stored of the window below a block.
    *
    * @param header
    *   the highest header that is missing, by its height and the hash its child names; the headers below it were not
    *   looked for, since they cannot be reached without it
    * @param bodies
    *   the headers, highest first, of the blocks whose bodies are missing and read, among those whose headers were
    *   found
    */
  final case class Missing(header: Option[(BigInt, BlockHash)], bodies: List[BlockHeader]):
    def isEmpty: Boolean = header.isEmpty && bodies.isEmpty
    def describe: String =
      val headers = header.map((number, hash) => s"the header of block $number (${hash.toHexString})")
      val bodyList = Option.when(bodies.nonEmpty)(s"the bodies of blocks ${bodies.map(_.number).mkString(", ")}")
      (headers ++ bodyList).mkString(" and ")

  /** What is missing of the window below `block`, which is itself stored; [[Missing.isEmpty]] when it is whole. Reads
    * only.
    *
    * @param proofOfWork
    *   whether the blocks above it may have ommers: the bodies are looked for only then
    */
  def check(
      block: BlockHeader,
      proofOfWork: Boolean,
      headerByHash: BlockHash => Option[BlockHeader],
      bodyByHash: BlockHash => Option[BlockBody]
  ): Missing =
    val number = block.number.value
    val depth = BigInt(HashDepth).min(number).toInt
    // the block and the headers below it that were found, highest first, and the first that was not
    @annotation.tailrec
    def walk(
        found: List[BlockHeader],
        child: BlockHeader,
        left: Int
    ): (List[BlockHeader], Option[(BigInt, BlockHash)]) =
      if left == 0 then (found.reverse, None)
      else
        val wanted = child.number.value - 1
        headerByHash(child.parentHash).filter(_.number.value == wanted) match
          case Some(parent) => walk(parent :: found, parent, left - 1)
          case None         => (found.reverse, Some((wanted, child.parentHash)))
    val (below, missingHeader) = walk(Nil, block, depth)
    val withBodies = if proofOfWork then (block :: below).take(OmmerBodies).filter(bodyRead) else Nil
    Missing(missingHeader, withBodies.filter(header => bodyByHash(header.hash).isEmpty))
