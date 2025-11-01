package com.chipprbots.ethereum.jsonrpc

import org.scalamock.scalatest.MockFactory

/** Support trait for JsonRpcController tests that use JsonRpcControllerFixture.
  *
  * In Scala 3, MockFactory requires TestSuite self-type, which anonymous classes
  * created by 'new JsonRpcControllerFixture' don't satisfy. This trait provides
  * the test spec itself as an implicit MockFactory so that JsonRpcControllerFixture
  * can delegate mock creation to the test spec.
  *
  * Usage: Mix this trait into test specs that use JsonRpcControllerFixture.
  */
trait JsonRpcControllerTestSupport { self: MockFactory =>
  implicit protected def mockFactoryProvider: MockFactory = this
}
