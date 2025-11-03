package com.chipprbots.scalanet.discovery.ethereum

import java.nio.charset.StandardCharsets.UTF_8

import cats.implicits._

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scodec.bits.ByteVector

/** Key value pairs that get added to the local ENR record as well as used
  * as a critera for accepting remote ENRs.
  */
trait KeyValueTag {

  /** Add a key-value pair to the outgoing ENR record.
    * Return None if this tag is used only for filtering.
    */
  def toAttr: Option[(ByteVector, ByteVector)]

  /** Apply a filter on incoming ENR records. */
  def toFilter: KeyValueTag.EnrFilter
}

object KeyValueTag {

  /** Return either a rejection message or unit, to accept the ENR. */
  type EnrFilter = EthereumNodeRecord => Either[String, Unit]

  def toFilter(tags: List[KeyValueTag]): EnrFilter = {
    val filters = tags.map(_.toFilter)
    enr => filters.traverse(_(enr)).void
  }

  class StringEquals(key: String, value: String) extends KeyValueTag {
    private val keyBytes =
      EthereumNodeRecord.Keys.key(key)

    private val valueBytes =
      ByteVector(value.getBytes(UTF_8))

    override val toAttr: Option[(ByteVector, ByteVector)] =
      Some(keyBytes -> valueBytes)

    override val toFilter: EnrFilter = enr =>
      enr.content.attrs.get(keyBytes) match {
        case Some(otherBytes) if otherBytes != valueBytes =>
          Try(new String(otherBytes.toArray, UTF_8)) match {
            case Success(otherValue) =>
              Left(s"$key mismatch; $otherValue != $value")

            case Failure(_) =>
              Left(s"$key mismatch; $otherBytes != $valueBytes")
          }

        case Some(_) =>
          Right(())

        case None =>
          Left(s"$key is missing; expected $value")
      }
  }

  object NetworkId {
    def apply(networkId: String) =
      new StringEquals("network-id", networkId)
  }
}
