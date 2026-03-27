package com.chipprbots.ethereum.vm.utils

import java.io.File

import org.apache.pekko.util.ByteString
import io.circe.{Decoder, Error}
import io.circe.parser.decode
import scala.io.Source

object Utils {

  implicit val paramDecoder: Decoder[ABI.Param] = Decoder.forProduct2("name", "type")(ABI.Param.apply)

  implicit val abiDecoder: Decoder[ABI] = Decoder.instance { c =>
    for {
      typ     <- c.get[String]("type")
      name    <- c.getOrElse[String]("name")("")
      inputs  <- c.getOrElse[Seq[ABI.Param]]("inputs")(Nil)
      outputs <- c.getOrElse[Seq[ABI.Param]]("outputs")(Nil)
    } yield ABI(typ, name, inputs, outputs)
  }

  def loadContractCodeFromFile(file: File): ByteString = {
    val src = Source.fromFile(file)
    val raw = try { src.mkString } finally { src.close() }
    ByteString(raw.trim.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  }

  def loadContractAbiFromFile(file: File): Either[Error, List[ABI]] = {
    val src = Source.fromFile(file)
    val raw = try { src.mkString } finally { src.close() }
    decode[List[ABI]](raw)
  }

}
