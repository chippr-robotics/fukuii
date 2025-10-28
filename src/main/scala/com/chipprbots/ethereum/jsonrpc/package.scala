package com.chipprbots.ethereum

import cats.effect.IO

package object jsonrpc {
  type ServiceResponse[T] = IO[Either[JsonRpcError, T]]
}
