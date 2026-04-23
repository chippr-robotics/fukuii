package com.chipprbots.ethereum.jsonrpc.graphql

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import sangria.ast.Document
import sangria.execution.{ErrorWithResolver, Executor, HandledException, QueryAnalysisError, QueryReducer}
import sangria.marshalling.ResultMarshaller
import sangria.marshalling.circe._
import sangria.parser.{QueryParser, SyntaxError}

import com.chipprbots.ethereum.utils.Logger

/** Executes GraphQL queries against the Fukuii `GraphQLSchema`.
  *
  * Bridges Sangria's `Future`-based executor into cats-effect `IO` so call sites match the JSON-RPC handlers. Returns
  * `(statusCode, jsonBody)` — geth returns 400 when the response has any errors, 200 otherwise. Hive testcases rely on
  * this.
  */
class GraphQLService(
    graphQLContext: GraphQLContext,
    maxQueryDepth: Int = GraphQLSchema.MaxQueryDepth,
    executionTimeout: FiniteDuration = 30.seconds
)(implicit ec: ExecutionContext, @annotation.unused runtime: IORuntime)
    extends Logger {

  private val schema = GraphQLSchema.schema

  private val depthReducer = QueryReducer.rejectMaxDepth[GraphQLContext](maxQueryDepth)

  /** Parse + validate + execute a GraphQL request, returning the JSON response body and a preferred HTTP status code
    * (200 for success, 400 for query errors).
    */
  def execute(query: String, variables: Option[Json], operationName: Option[String]): IO[(Int, Json)] = {
    val parsed: Either[Json, Document] = QueryParser.parse(query) match {
      case Success(doc) => Right(doc)
      case Failure(e: SyntaxError) =>
        Left(errorEnvelope(e.getMessage))
      case Failure(other) =>
        Left(errorEnvelope(other.getMessage))
    }

    parsed match {
      case Left(errorJson) => IO.pure((400, errorJson))
      case Right(doc) =>
        val vars = variables.getOrElse(Json.obj())
        val fut = Executor
          .execute(
            schema,
            doc,
            userContext = graphQLContext,
            operationName = operationName,
            variables = vars,
            queryReducers = List(depthReducer),
            exceptionHandler = graphQLExceptionHandler
          )
          .map(json => (statusForResult(json), json))
          .recover {
            case e: QueryAnalysisError =>
              (400, e.resolveError)
            case e: ErrorWithResolver =>
              (500, e.resolveError)
          }
        IO.fromFuture(IO(fut)).timeout(executionTimeout).handleErrorWith { t =>
          log.error("GraphQL execution failed", t)
          IO.pure((500, errorEnvelope(s"internal error: ${t.getMessage}")))
        }
    }
  }

  /** Geth returns 400 whenever a GraphQL response has a non-empty `errors` array, regardless of whether the query
    * parsed successfully. Hive testcases rely on this.
    */
  private def statusForResult(json: Json): Int =
    json.hcursor.downField("errors").focus.flatMap(_.asArray) match {
      case Some(arr) if arr.nonEmpty => 400
      case _                         => 200
    }

  private def errorEnvelope(message: String): Json =
    Json.obj("errors" -> Json.arr(Json.obj("message" -> Json.fromString(message))))

  /** Emits errors in the canonical geth/graphql-java format hive testcases expect:
    * {{{
    *   "errors": [{
    *     "message": "Exception while fetching data (/<path>) : <reason>",
    *     "path": [...],
    *     "locations": [...],
    *     "extensions": {
    *       "classification": "DataFetchingException",
    *       "errorCode": <code>,        // optional
    *       "errorMessage": "<msg>"     // optional
    *     }
    *   }]
    * }}}
    * Resolvers throw [[GraphQLDataFetchingError]] to control the `errorCode` / `errorMessage` extensions. Any other
    * throwable gets wrapped as a generic DataFetchingException so hive testcases matching on the
    * `extensions.classification` string still pass.
    */
  private val graphQLExceptionHandler: sangria.execution.ExceptionHandler =
    sangria.execution.ExceptionHandler {
      case (m: ResultMarshaller, e: GraphQLDataFetchingError) =>
        handledDataFetching(m, e.message, e.errorCode, e.errorMessage)
      case (m: ResultMarshaller, e: GraphQLUserError) =>
        // Legacy path: plain UserFacingError — wrap as DataFetchingException without codes.
        handledDataFetching(m, e.getMessage, None, None)
      case (m: ResultMarshaller, t) =>
        handledDataFetching(m, Option(t.getMessage).getOrElse("internal error"), None, None)
    }

  private def handledDataFetching(
      m: ResultMarshaller,
      reason: String,
      errorCode: Option[Int],
      errorMessage: Option[String]
  ): HandledException = {
    val classificationField = Vector("classification" -> m.scalarNode("DataFetchingException", "String", Set.empty))
    val codeField = errorCode.map(c => "errorCode" -> m.scalarNode(c, "Int", Set.empty)).toVector
    val msgField = errorMessage.map(em => "errorMessage" -> m.scalarNode(em, "String", Set.empty)).toVector
    val fields = classificationField ++ codeField ++ msgField
    HandledException(
      reason,
      additionalFields = fields.toMap,
      addFieldsInExtensions = true,
      addFieldsInError = false
    )
  }
}

object GraphQLService {

  /** Parses an incoming HTTP body into a GraphQL request. Accepts:
    *   - `application/json` with `{"query": "...", "variables": {...}, "operationName": "..."}`
    *   - Raw `application/graphql` where the body is just the query string.
    */
  final case class GraphQLRequest(query: String, variables: Option[Json], operationName: Option[String])

  def parseJsonBody(body: String): Either[String, GraphQLRequest] =
    io.circe.parser.parse(body) match {
      case Left(err) => Left(s"invalid JSON body: ${err.message}")
      case Right(json) =>
        val cursor = json.hcursor
        cursor.get[String]("query") match {
          case Left(e) => Left(s"missing 'query' field: ${e.message}")
          case Right(q) =>
            val variables = cursor.downField("variables").focus.filterNot(_.isNull)
            val opName = cursor.get[String]("operationName").toOption.filter(_.nonEmpty)
            Right(GraphQLRequest(q, variables, opName))
        }
    }
}
