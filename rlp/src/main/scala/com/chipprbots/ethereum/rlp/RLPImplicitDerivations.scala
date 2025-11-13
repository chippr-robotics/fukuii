package com.chipprbots.ethereum.rlp

/** Compatibility layer for automatic derivation of RLP codecs.
  *
  * This object re-exports the Scala 3 Mirror-based derivation from [[RLPDerivation]]. The old Shapeless 2-based
  * implementation has been removed.
  *
  * For new code, prefer using the `derives` clause directly:
  * {{{
  *   case class MyData(field1: Int, field2: String) derives RLPCodec
  * }}}
  *
  * For existing code using explicit derivation:
  * {{{
  *   import RLPImplicitDerivations._
  *   given RLPCodec[MyData] = deriveLabelledGenericRLPCodec[MyData]
  * }}}
  */
object RLPImplicitDerivations {
  // Re-export core types and functions from RLPDerivation
  export RLPDerivation.{DerivationPolicy, FieldInfo, RLPListEncoder, RLPListDecoder}

  // Re-export the derivation policy default
  given defaultDerivationPolicy: DerivationPolicy = DerivationPolicy.default

  /** Derive RLP codec for a case class using Scala 3 Mirror. This replaces the old Shapeless-based
    * deriveLabelledGenericRLPCodec.
    *
    * Note: For explicit derivation, call RLPDerivation.derivedCodec[T] directly. This given instance is for automatic
    * implicit resolution.
    *
    * @example
    *   {{{ import RLPImplicitDerivations.{given, *} import RLPImplicits.{given, *} // for base type encoders/decoders
    *   case class MyData(field1: Int, field2: String) val codec = summon[RLPCodec[MyData]] // automatically derived }}}
    */
  transparent inline given deriveLabelledGenericRLPCodec[T](using
      m: scala.deriving.Mirror.ProductOf[T],
      ct: scala.reflect.ClassTag[T]
  ): RLPCodec[T] =
    RLPDerivation.derivedCodec[T]

  /** Derive RLP encoder for a case class.
    *
    * Note: For explicit derivation, call RLPDerivation.derivedEncoder[T] directly. This given instance is for automatic
    * implicit resolution.
    */
  transparent inline given deriveLabelledGenericRLPEncoder[T](using
      m: scala.deriving.Mirror.ProductOf[T]
  ): RLPEncoder[T] =
    RLPDerivation.derivedEncoder[T]

  /** Derive RLP decoder for a case class.
    *
    * Note: For explicit derivation, call RLPDerivation.derivedDecoder[T] directly. This given instance is for automatic
    * implicit resolution.
    */
  transparent inline given deriveLabelledGenericRLPDecoder[T](using
      m: scala.deriving.Mirror.ProductOf[T],
      ct: scala.reflect.ClassTag[T]
  ): RLPDecoder[T] =
    RLPDerivation.derivedDecoder[T]
}
