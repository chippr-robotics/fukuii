package com.chipprbots.ethereum.jsonrpc.server.http

import java.net.InetSocketAddress

import org.apache.pekko.http.scaladsl.Http

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.utils.Logger

/** Keeps the binding of one HTTP-based JSON-RPC server (HTTP or WebSocket) so that it can be stopped and started again
  * while the node runs (spec 029 FR-052, FR-055: the dashboard's `rpc stop` and `rpc start`). It also says, for the
  * dashboard's RPC view, whether the server is bound ([[RpcCallStats.listening]]) or was stopped by the operator
  * ([[RpcCallStats.stopped]]); those writes are made inside the transitions, never from a callback that could run after
  * a later transition.
  *
  * States: stopped, binding, bound, stopping. Every transition is taken under one lock, and only the futures run
  * outside it. A repeated call is harmless: `start` while binding gives the pending future, `start` while bound gives
  * the binding, `stop` while stopped succeeds, `stop` while stopping gives the pending future. The two calls that cross
  * a transition in flight (`stop` while binding, `start` while stopping) fail with an [[IllegalStateException]] that
  * says so; the caller tries again.
  *
  * `stop` terminates the binding rather than only unbinding it: an unbound server keeps serving the connections it
  * already has, so a client on a kept-alive connection would still be answered by a server shown as stopped.
  * Termination refuses new connections, lets requests in flight finish, and closes every connection at `hardDeadline`.
  *
  * @param transport
  *   one of [[RpcCallStats.Transports]]
  * @param bind
  *   one bind of the server's route: called once per `start`, writes no stats
  * @param hardDeadline
  *   how long requests in flight are given on `stop`. Termination does not close an upgraded WebSocket connection (it
  *   is not a request in flight): a server that holds such connections closes them itself in `beforeTerminate`
  * @param beforeTerminate
  *   run once at each `stop` of a bound server, before the binding is terminated; it must not block
  */
final class ServerLifecycle(
    transport: String,
    bind: () => Future[Http.ServerBinding],
    stats: RpcCallStats = RpcCallStats.Node,
    val hardDeadline: FiniteDuration = ServerLifecycle.DefaultHardDeadline,
    beforeTerminate: () => Unit = () => ()
)(using ec: ExecutionContext)
    extends Logger:
  import ServerLifecycle.*

  private var state: State = State.Stopped

  /** Binds the server; a server that is bound or binding is left as it is. A failed bind leaves it stopped and is said
    * to the RPC view as not listening.
    */
  def start(): Future[Http.ServerBinding] = synchronized {
    state match
      case State.Bound(binding)  => Future.successful(binding)
      case State.Binding(future) => future
      case State.Stopping(_) =>
        Future.failed(new IllegalStateException(s"the $transport server is still stopping"))
      case State.Stopped =>
        val bound = Try(bind()) match
          case Success(future) => future
          case Failure(ex)     => Future.failed(ex)
        // The state is `Binding` before the bind can complete, and the caller's future completes only after the
        // state is settled, so a caller that waits for it and then calls `stop` finds the state already `Bound`.
        val settled = Promise[Http.ServerBinding]()
        state = State.Binding(settled.future)
        bound.onComplete { result =>
          settleBind(result)
          settled.complete(result)
        }
        settled.future
  }

  /** Stops listening and closes the connections: `beforeTerminate` runs first, then the binding is terminated, which
    * refuses new connections, lets requests in flight finish and closes the connections still open at `hardDeadline`.
    * The future completes when the port is free to bind again.
    */
  def stop(): Future[Unit] = synchronized {
    state match
      case State.Stopped          => Future.unit
      case State.Stopping(future) => future
      case State.Binding(_) =>
        Future.failed(new IllegalStateException(s"the $transport server is still starting"))
      case State.Bound(binding) =>
        log.info(s"Stopping the JSON-RPC $transport server on ${binding.localAddress}")
        val settled = Promise[Unit]()
        state = State.Stopping(settled.future)
        try beforeTerminate()
        catch case NonFatal(ex) => log.error(s"Closing the JSON-RPC $transport connections failed", ex)
        val terminating: Future[Any] =
          try binding.terminate(hardDeadline)
          catch case NonFatal(ex) => Future.failed(ex)
        terminating.onComplete { result =>
          val outcome = result.map(_ => ())
          settleStop(outcome)
          settled.complete(outcome)
        }
        settled.future
  }

  /** Where the server listens, while it is bound. */
  def boundAddress: Option[InetSocketAddress] = synchronized {
    state match
      case State.Bound(binding) => Some(binding.localAddress)
      case _                    => None
  }

  def phase: Phase = synchronized {
    state match
      case State.Stopped     => Phase.Stopped
      case State.Binding(_)  => Phase.Starting
      case State.Bound(_)    => Phase.Bound
      case State.Stopping(_) => Phase.Stopping
  }

  def isBound: Boolean = phase == Phase.Bound

  private def settleBind(result: Try[Http.ServerBinding]): Unit = synchronized {
    state match
      case State.Binding(_) =>
        result match
          case Success(binding) =>
            state = State.Bound(binding)
            stats.listening(transport, isListening = true)
          case Failure(ex) =>
            state = State.Stopped
            stats.listening(transport, isListening = false)
            log.error(s"Cannot start the JSON-RPC $transport server", ex)
      case _ => ()
  }

  private def settleStop(result: Try[Unit]): Unit = synchronized {
    state match
      case State.Stopping(_) =>
        state = State.Stopped
        result match
          case Success(_) =>
            stats.stopped(transport)
            log.info(s"The JSON-RPC $transport server is stopped")
          case Failure(ex) =>
            // The binding cannot be used again; what state the socket is in is not known, so it is not called stopped.
            stats.listening(transport, isListening = false)
            log.error(s"The JSON-RPC $transport server did not stop cleanly", ex)
      case _ => ()
  }

object ServerLifecycle:

  private enum State:
    case Stopped
    case Binding(pending: Future[Http.ServerBinding])
    case Bound(binding: Http.ServerBinding)
    case Stopping(pending: Future[Unit])

  /** How long requests in flight, and WebSocket connections, are given on `stop`. */
  val DefaultHardDeadline: FiniteDuration = 3.seconds

  enum Phase:
    case Stopped, Starting, Bound, Stopping
