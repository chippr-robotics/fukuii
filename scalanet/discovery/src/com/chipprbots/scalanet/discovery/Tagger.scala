package com.chipprbots.scalanet.discovery

/** Helper class to make it easier to tag raw types such as BitVector
  * to specializations so that the compiler can help make sure we are
  * passing the right values to methods.
  *
  * This now uses Scala 3's type system instead of Shapeless tags.
  *
  * Using it like so:
  *
  * ```
  * trait MyTypeTag
  * object MyType extends Tagger[ByteVector, MyTypeTag]
  * type MyType = MyType.Tagged
  *
  * val myThing = MyType(ByteVector.empty)
  * ```
  *
  */
trait Tagger[U, T] {
  // In Scala 3, we use opaque type aliases for type-level tagging
  // This provides zero-cost abstractions with type safety
  opaque type Tagged = U
  
  object Tagged {
    def apply(underlying: U): Tagged = underlying
    
    extension (tagged: Tagged) {
      def value: U = tagged
    }
  }
  
  def apply(underlying: U): Tagged = Tagged(underlying)
}
