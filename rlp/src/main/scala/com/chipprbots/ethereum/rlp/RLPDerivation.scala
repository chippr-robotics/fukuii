package com.chipprbots.ethereum.rlp

import scala.compiletime.{*, given}
import scala.deriving.Mirror
import scala.reflect.ClassTag

/** Scala 3 native derivation for RLP codecs using Mirror type class.
  * 
  * This replaces the Shapeless 2-based derivation in RLPImplicitDerivations.
  * 
  * Usage:
  * {{{
  *   case class MyData(field1: Int, field2: String) derives RLPCodec
  * }}}
  */
object RLPDerivation {

  /** Derivation policy for controlling encoding/decoding behavior */
  case class DerivationPolicy(
      // Whether to treat optional fields at the end of the list like
      // they can be omitted from the RLP list, or inserted as a value,
      // as opposed to a list of 0 or 1 items.
      omitTrailingOptionals: Boolean
  )
  object DerivationPolicy {
    val default: DerivationPolicy = DerivationPolicy(omitTrailingOptionals = false)
  }

  /** Support introspecting on what happened during encoding the tail. */
  case class FieldInfo(isOptional: Boolean)

  /** Case classes get encoded as lists, not values, which is an extra piece of information we want to be able to rely
    * on during derivation.
    */
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
  }

  // Type-level helpers for checking if a type is Option[_]
  private type IsOption[T] <: Boolean = T match {
    case Option[?] => true
    case _ => false
  }

  /** Compile-time encoder for product fields */
  private inline def encodeProductFields[T <: Tuple](
      values: T,
      labels: Tuple,
      policy: DerivationPolicy
  ): (RLPList, List[FieldInfo]) = {
    inline values match {
      case EmptyTuple => (RLPList(), Nil)
      case head *: tail =>
        inline erasedValue[IsOption[head.type]] match {
          case _: true =>
            // Optional field
            val headEncoded = summonInline[RLPEncoder[head.type]].encode(head)
            val (tailList, tailInfos) = encodeProductFields(tail, labels, policy)
            val hInfo = FieldInfo(isOptional = true)
            
            val finalList = if (policy.omitTrailingOptionals && tailInfos.forall(_.isOptional)) {
              // Trailing optional - can be inserted as value or omitted
              headEncoded match {
                case RLPList(items @ _*) if items.length == 1 =>
                  items.head +: tailList
                case RLPList() if tailList.items.isEmpty =>
                  tailList
                case hRLP =>
                  hRLP +: tailList
              }
            } else {
              // Non-trailing optional - insert as list
              headEncoded +: tailList
            }
            (finalList, hInfo :: tailInfos)
            
          case _: false =>
            // Non-optional field
            val headEncoded = summonInline[RLPEncoder[head.type]].encode(head)
            val (tailList, tailInfos) = encodeProductFields(tail, labels, policy)
            val hInfo = FieldInfo(isOptional = false)
            (headEncoded +: tailList, hInfo :: tailInfos)
        }
    }
  }

  /** Compile-time decoder for product fields */
  private inline def decodeProductFields[T <: Tuple](
      items: List[RLPEncodeable],
      labels: Tuple,
      policy: DerivationPolicy
  ): (T, List[FieldInfo]) = {
    inline erasedValue[T] match {
      case _: EmptyTuple.type =>
        items match {
          case Nil => (EmptyTuple.asInstanceOf[T], Nil)
          case _ if policy.omitTrailingOptionals => (EmptyTuple.asInstanceOf[T], Nil)
          case _ => 
            throw RLPException(
              s"Unexpected items at the end of the RLPList: ${items.size} leftover items.",
              RLPList(items: _*)
            )
        }
      case _: (head *: tail) =>
        inline erasedValue[IsOption[head]] match {
          case _: true =>
            // Optional field
            val hInfo = FieldInfo(isOptional = true)
            items match {
              case Nil if policy.omitTrailingOptionals =>
                val (tailDecoded, tailInfos) = decodeProductFields[tail](Nil, labels, policy)
                val noneValue = None.asInstanceOf[head]
                ((noneValue *: tailDecoded).asInstanceOf[T], hInfo :: tailInfos)
              case Nil =>
                throw RLPException(s"RLPList is empty for optional field.", RLPList())
              case rlpHead :: rlpTail =>
                val (tailDecoded, tailInfos) = decodeProductFields[tail](rlpTail, labels, policy)
                val decoder = summonInline[RLPDecoder[head]]
                val headValue = try {
                  if (policy.omitTrailingOptionals && tailInfos.forall(_.isOptional)) {
                    // Trailing optional - try as value wrapped in list
                    try decoder.decode(RLPList(rlpHead))
                    catch {
                      case _: Throwable => None.asInstanceOf[head]
                    }
                  } else {
                    decoder.decode(rlpHead)
                  }
                } catch {
                  case ex: Throwable =>
                    throw RLPException(s"Cannot decode optional field: ${ex.getMessage}", List(rlpHead))
                }
                ((headValue *: tailDecoded).asInstanceOf[T], hInfo :: tailInfos)
            }
          case _: false =>
            // Non-optional field
            val hInfo = FieldInfo(isOptional = false)
            items match {
              case Nil =>
                throw RLPException(s"RLPList is empty for non-optional field.", RLPList())
              case rlpHead :: rlpTail =>
                val decoder = summonInline[RLPDecoder[head]]
                val headValue = try {
                  decoder.decode(rlpHead)
                } catch {
                  case ex: Throwable =>
                    throw RLPException(s"Cannot decode field: ${ex.getMessage}", List(rlpHead))
                }
                val (tailDecoded, tailInfos) = decodeProductFields[tail](rlpTail, labels, policy)
                ((headValue *: tailDecoded).asInstanceOf[T], hInfo :: tailInfos)
            }
        }
    }
  }

  /** Derive encoder for product types (case classes) */
  given derivedEncoder[T](using m: Mirror.ProductOf[T])(using policy: DerivationPolicy = DerivationPolicy.default): RLPEncoder[T] =
    new RLPEncoder[T] {
      override def encode(obj: T): RLPEncodeable = {
        val tuple = Tuple.fromProduct(obj.asInstanceOf[Product])
        val labels = constValueTuple[m.MirroredElemLabels]
        val (list, _) = encodeProductFields(tuple.asInstanceOf[Tuple], labels, policy)
        list
      }
    }

  /** Derive decoder for product types (case classes) */
  given derivedDecoder[T](using m: Mirror.ProductOf[T], ct: ClassTag[T])(using policy: DerivationPolicy = DerivationPolicy.default): RLPDecoder[T] =
    new RLPDecoder[T] {
      override def decode(rlp: RLPEncodeable): T = rlp match {
        case list: RLPList =>
          val labels = constValueTuple[m.MirroredElemLabels]
          val (decoded, _) = decodeProductFields[m.MirroredElemTypes](
            list.items.toList,
            labels,
            policy
          )
          m.fromProduct(decoded.asInstanceOf[Product])
        case _ =>
          throw RLPException(s"Cannot decode ${ct.runtimeClass.getSimpleName}: expected an RLPList.", rlp)
      }
    }

  /** Derive both encoder and decoder for product types */
  given derivedCodec[T](using 
      m: Mirror.ProductOf[T],
      ct: ClassTag[T]
  ): RLPCodec[T] =
    val enc = derivedEncoder[T](using m)
    val dec = derivedDecoder[T](using m, ct)
    RLPCodec[T](enc, dec)
}
