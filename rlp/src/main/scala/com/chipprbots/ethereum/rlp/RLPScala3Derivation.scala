package com.chipprbots.ethereum.rlp

import scala.compiletime.*
import scala.deriving.*
import scala.reflect.ClassTag

/** Automatically derive RLP codecs for case classes using Scala 3's deriving.
  * 
  * This provides a simpler derivation mechanism for Scala 3 that works similarly
  * to the Shapeless 2 version but uses Scala 3's built-in deriving capabilities.
  */
object RLPScala3Derivation {

  case class DerivationPolicy(
      omitTrailingOptionals: Boolean
  )
  object DerivationPolicy {
    val default: DerivationPolicy = DerivationPolicy(omitTrailingOptionals = false)
  }

  case class FieldInfo(isOptional: Boolean)

  /** Support introspecting on what happened during encoding the tail. */
  trait RLPListEncoder[T] extends RLPEncoder[T] {
    def encodeList(obj: T): (RLPList, List[FieldInfo])

    override def encode(obj: T): RLPEncodeable =
      encodeList(obj)._1
  }

  object RLPListEncoder {
    def apply[T](f: T => (RLPList, List[FieldInfo])): RLPListEncoder[T] =
      new RLPListEncoder[T] {
        override def encodeList(obj: T) = f(obj)
      }
    
    inline def derived[T](using m: Mirror.ProductOf[T]): RLPListEncoder[T] = 
      RLPListEncoderImpl[T](summonEncoders[m.MirroredElemTypes])
  }
  
  private class RLPListEncoderImpl[T](encoders: List[RLPEncoder[?]]) extends RLPListEncoder[T] {
    override def encodeList(obj: T): (RLPList, List[FieldInfo]) = {
      val elems = obj.asInstanceOf[Product].productIterator.toList
      encodeElements(elems, encoders)
    }
  }

  /** Specialized decoder for case classes that only accepts RLPList for input. */
  trait RLPListDecoder[T] extends RLPDecoder[T] {
    protected def ct: ClassTag[T]
    def decodeList(items: List[RLPEncodeable]): (T, List[FieldInfo])

    override def decode(rlp: RLPEncodeable): T =
      rlp match {
        case list: RLPList =>
          decodeList(list.items.toList)._1
        case _ =>
          throw RLPException(s"Cannot decode ${ct.runtimeClass.getSimpleName}: expected an RLPList.", rlp)
      }
  }

  object RLPListDecoder {
    def apply[T: ClassTag](f: List[RLPEncodeable] => (T, List[FieldInfo])): RLPListDecoder[T] =
      new RLPListDecoder[T] {
        override val ct = implicitly[ClassTag[T]]
        override def decodeList(items: List[RLPEncodeable]) = f(items)
      }
    
    inline def derived[T](using m: Mirror.ProductOf[T], ct: ClassTag[T]): RLPListDecoder[T] = 
      RLPListDecoderImpl[T](summonDecoders[m.MirroredElemTypes], m, ct)
  }

  private class RLPListDecoderImpl[T](
    decoders: List[RLPDecoder[?]], 
    mirror: Mirror.ProductOf[T], 
    override val ct: ClassTag[T]
  ) extends RLPListDecoder[T] {
    override def decodeList(items: List[RLPEncodeable]): (T, List[FieldInfo]) = {
      val decoded = decodeElements(items, decoders)
      val tuple = decoded.asInstanceOf[mirror.MirroredElemTypes]
      (mirror.fromProduct(tuple), decoded.map(_ => FieldInfo(isOptional = false)))
    }
  }

  // Summon encoders for all elements
  inline def summonEncoders[T <: Tuple]: List[RLPEncoder[?]] = 
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts) => summonInline[RLPEncoder[t]] :: summonEncoders[ts]
    }

  private def encodeElements(elems: List[Any], encoders: List[RLPEncoder[?]]): (RLPList, List[FieldInfo]) = {
    val encoded = elems.zip(encoders).map { case (elem, encoder) =>
      encoder.asInstanceOf[RLPEncoder[Any]].encode(elem)
    }
    (RLPList(encoded*), elems.map(_ => FieldInfo(isOptional = false)))
  }

  // Summon decoders for all elements
  inline def summonDecoders[T <: Tuple]: List[RLPDecoder[?]] = 
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts) => summonInline[RLPDecoder[t]] :: summonDecoders[ts]
    }

  private def decodeElements(items: List[RLPEncodeable], decoders: List[RLPDecoder[?]]): List[Any] = {
    items.zip(decoders).map { case (item, decoder) =>
      decoder.asInstanceOf[RLPDecoder[Any]].decode(item)
    }
  }
}
