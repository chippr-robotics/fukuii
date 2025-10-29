package com.chipprbots.ethereum.rlp

// Simple test to verify Scala 3 syntax works
object TestScala3 {
  trait Encoder[T] {
    def encode(t: T): String
  }
  
  given Encoder[Int] with
    def encode(t: Int): String = t.toString
    
  given stringEncoder: Encoder[String] with
    def encode(t: String): String = t
}
