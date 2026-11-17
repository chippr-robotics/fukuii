package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import scala.reflect.ClassTag

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.pow.PoWMining
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader

/** Provides everything related to consensus. Different mining protocols are implemented in sub-packages.
  */
package object mining:
  final type GetBlockHeaderByHash = ByteString => Option[BlockHeader]

  /** The block with the given hash and the `n - 1` blocks below it, oldest first, as the ommers rules read them
    * ([[OmmerAncestor]]); fewer where they are not stored.
    */
  final type GetNBlocksBack = (ByteString, Int) => Seq[OmmerAncestor]

  /** An ancestor as the ommers rules read it (B-302): its header, and the ommers its body holds where they are known. A
    * header whose ommers hash is the empty list's has none, and its body is not read: core-geth's `VerifyUncles` reads
    * the block of an ancestor only where its uncle hash is not the empty one.
    *
    * @param ommers
    *   the ommers of the ancestor's body; `None` where it names some and its body is not stored
    */
  final case class OmmerAncestor(header: BlockHeader, ommers: Option[Seq[BlockHeader]])

  object OmmerAncestor:
    def of(block: Block): OmmerAncestor = OmmerAncestor(block.header, Some(block.body.uncleNodesList))

    /** The ancestor with this header; its body is looked for only where the header names ommers. */
    def of(header: BlockHeader, body: => Option[BlockBody]): OmmerAncestor =
      if header.ommersHash.value == BlockHeader.EmptyOmmers then OmmerAncestor(header, Some(Nil))
      else OmmerAncestor(header, body.map(_.uncleNodesList))

  def wrongMiningArgument[T <: Mining: ClassTag](mining: Mining): Nothing =
    val requiredClass = implicitly[ClassTag[T]].runtimeClass
    val msg = s"Mining is of ${mining.getClass} it should be of $requiredClass"
    throw new IllegalArgumentException(msg)

  def wrongValidatorsArgument[T <: Validators: ClassTag](validators: Validators): Nothing =
    val requiredClass = implicitly[ClassTag[T]].runtimeClass
    val msg = s"validators are of ${validators.getClass} it should be of $requiredClass"
    throw new IllegalArgumentException(msg)

  def wrongBlockGeneratorArgument[T <: BlockGenerator: ClassTag](blockGenerator: BlockGenerator): Nothing =
    val requiredClass = implicitly[ClassTag[T]].runtimeClass
    val msg = s"Block generator is of ${blockGenerator.getClass} it should be of $requiredClass"
    throw new IllegalArgumentException(msg)

  extension (mining: Mining)
    /** There are APIs that expect that the standard Ethash mining is running and so depend on either its configuration
      * or general PoW semantics. This is a method that can handle such cases via a respective if/then/else construct:
      * if we run under [[com.chipprbots.ethereum.consensus.pow.PoWMining EthashConsensus]] then the `_then` function is
      * called, otherwise the `_else` value is computed.
      */
    def ifEthash[A](_then: PoWMining => A)(_else: => A): A =
      mining match
        case ethash: PoWMining => _then(ethash)
        case _                 => _else
