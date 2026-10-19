package com.chipprbots.ethereum.jsonrpc.conformance

import org.json4s.*
import org.json4s.native.JsonMethods.parse

import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse

/** execution-apis `.io` test files and the comparison hive's rpc-compat simulator applies to them
  * (`simulators/ethereum/rpc-compat/main.go`, hive master ccf3f463).
  */
object RpcCompatVectors:

  /** One `.io` file: `>>` request and `<<` response lines in order, `//` comments. `speconly` in a comment means only
    * the response's schema is checked, unless the response is an error.
    */
  final case class Vector(comment: String, specOnly: Boolean, exchanges: Seq[(JValue, JValue)])

  def read(text: String): Vector =
    val lines = text.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    val comments = lines.filter(_.startsWith("//")).map(_.stripPrefix("//").trim)
    val requests = lines.filter(_.startsWith(">>")).map(l => parse(l.stripPrefix(">>").trim))
    val responses = lines.filter(_.startsWith("<<")).map(l => parse(l.stripPrefix("<<").trim))
    require(requests.size == responses.size, s"unpaired messages: ${requests.size} >> vs ${responses.size} <<")
    Vector(comments.mkString(" "), comments.exists(_.startsWith("speconly")), requests.zip(responses))

  /** The response as it goes on the wire. */
  def wire(response: JsonRpcResponse): JValue =
    val body = response.error match
      case Some(error) =>
        "error" -> JObject(
          List("code" -> JInt(error.code), "message" -> JString(error.message)) ++ error.data.map("data" -> _).toList
        )
      case None => "result" -> response.result.getOrElse(JNull)
    JObject("jsonrpc" -> JString(response.jsonrpc), "id" -> response.id, body)

  private def withoutMessage(error: JValue): JValue = error.removeField {
    case ("message", _) => true
    case _              => false
  }

  /** hive drops `error.message` when both the client's and the expected response carry an error: clients word errors
    * their own way. The code and data still have to match.
    */
  def redactErrorMessages(actual: JValue, expected: JValue): (JValue, JValue) =
    (actual \ "error", expected \ "error") match
      case (a: JObject, e: JObject) =>
        (actual.replace(List("error"), withoutMessage(a)), expected.replace(List("error"), withoutMessage(e)))
      case _ => (actual, expected)

  /** `None` when hive would pass the exchange, else what differs. */
  def mismatch(vector: Vector, actual: JValue, expected: JValue): Option[String] =
    if vector.specOnly && (actual \ "error") == JNothing then None // schema-only; fukuii does not load the OpenRPC spec
    else
      val (a, e) = redactErrorMessages(actual, expected)
      Option.when(a != e) {
        import org.json4s.native.JsonMethods.{compact, render}
        s"expected ${compact(render(e))}\n  actual ${compact(render(a))}"
      }
