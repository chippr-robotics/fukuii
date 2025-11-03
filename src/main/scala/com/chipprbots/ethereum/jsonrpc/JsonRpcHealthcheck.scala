package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.healthcheck.HealthcheckResult

final case class JsonRpcHealthcheck[Response](
    name: String,
    healthCheck: Either[String, Response],
    info: Option[String] = None
) {

  def toResult: HealthcheckResult =
    healthCheck
      .fold(
        HealthcheckResult.error(name, _),
        _ => HealthcheckResult.ok(name, info)
      )

  def withPredicate(message: String)(predicate: Response => Boolean): JsonRpcHealthcheck[Response] =
    copy(healthCheck = healthCheck.filterOrElse(predicate, message))

  def collect[T](message: String)(collectFn: PartialFunction[Response, T]): JsonRpcHealthcheck[T] =
    copy(
      name = name,
      healthCheck = healthCheck.flatMap(collectFn.lift(_).toRight(message))
    )

  def withInfo(getInfo: Response => String): JsonRpcHealthcheck[Response] =
    copy(info = healthCheck.toOption.map(getInfo))
}

object JsonRpcHealthcheck {

  def fromServiceResponse[Response](name: String, f: ServiceResponse[Response]): IO[JsonRpcHealthcheck[Response]] =
    f.map(result =>
      JsonRpcHealthcheck(
        name,
        result.left.map[String](_.message)
      )
    ).handleError(t => JsonRpcHealthcheck(name, Left(t.getMessage())))

  def fromTask[Response](name: String, f: IO[Response]): IO[JsonRpcHealthcheck[Response]] =
    f.map(result =>
      JsonRpcHealthcheck(
        name,
        Right(result)
      )
    ).handleError(t => JsonRpcHealthcheck(name, Left(t.getMessage())))

}
