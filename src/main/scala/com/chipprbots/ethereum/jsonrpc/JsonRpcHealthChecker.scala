package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import com.chipprbots.ethereum.healthcheck.HealthcheckResponse

trait JsonRpcHealthChecker {
  def healthCheck(): IO[HealthcheckResponse]

  def readinessCheck(): IO[HealthcheckResponse]

  def handleResponse(responseF: IO[HealthcheckResponse]): IO[HealthcheckResponse] =
    responseF
      .map {
        case response if !response.isOK =>
          JsonRpcControllerMetrics.HealhcheckErrorCounter.increment()
          response
        case response => response
      }
      .handleErrorWith { t =>
        JsonRpcControllerMetrics.HealhcheckErrorCounter.increment()
        IO.raiseError(t)
      }
}
