package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.http.scaladsl.marshalling.Marshaller
import org.apache.pekko.http.scaladsl.marshalling.ToEntityMarshaller
import org.apache.pekko.http.scaladsl.model.ContentTypeRange
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.MediaTypes
import org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller

import org.json4s.Formats
import org.json4s.Serialization

/** Pekko HTTP support for json4s serialization Compatibility layer replacing
  * de.heikoseeberger.akkahttpjson4s.Json4sSupport
  */
trait Json4sSupport {
  implicit def serialization: Serialization

  implicit def formats: Formats

  implicit def json4sUnmarshaller[A <: AnyRef: Manifest]: FromEntityUnmarshaller[A] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(MediaTypes.`application/json`)
      .map { bytes =>
        serialization.read[A](bytes.utf8String)
      }

  implicit def json4sMarshaller[A <: AnyRef]: ToEntityMarshaller[A] =
    Marshaller.oneOf(
      Marshaller.withFixedContentType(MediaTypes.`application/json`) { (value: A) =>
        HttpEntity(MediaTypes.`application/json`, serialization.write(value: AnyRef))
      }
    )
}

object Json4sSupport extends Json4sSupport {
  implicit override val serialization: Serialization = org.json4s.native.Serialization
  implicit override val formats: Formats = org.json4s.DefaultFormats
}
