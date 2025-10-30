package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypeRange, HttpEntity, MediaTypes}
import org.apache.pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import org.json4s.{Formats, Serialization}

import scala.collection.immutable.Seq

/**
  * Pekko HTTP support for json4s serialization
  * Compatibility layer replacing de.heikoseeberger.akkahttpjson4s.Json4sSupport
  */
trait Json4sSupport {
  implicit def serialization: Serialization

  implicit def formats: Formats

  implicit def json4sUnmarshaller: FromEntityUnmarshaller[AnyRef] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(MediaTypes.`application/json`)
      .map { bytes =>
        serialization.read[AnyRef](bytes.utf8String)
      }

  implicit def json4sMarshaller[A <: AnyRef]: ToEntityMarshaller[A] =
    Marshaller.oneOf(
      Marshaller.withFixedContentType(MediaTypes.`application/json`) { value =>
        HttpEntity(MediaTypes.`application/json`, serialization.write(value))
      }
    )
}

object Json4sSupport extends Json4sSupport {
  override implicit val serialization: Serialization = org.json4s.native.Serialization
  override implicit val formats: Formats = org.json4s.DefaultFormats
}
