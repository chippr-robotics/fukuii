package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

import scala.collection.mutable

/** A clock that moves only when a test moves it: the retry and wait of the refresh run on it, so no test waits. */
final class ManualClock(start: Instant) extends Clock:
  private var current = start
  def advance(d: Duration): Unit = current = current.plus(d)
  override def instant(): Instant = current
  override def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this

/** The sleeper of a test: it records the wait and advances the manual clock by it. */
final class ManualSleeper(clock: ManualClock) extends Sleeper:
  val waits: mutable.ArrayBuffer[Duration] = mutable.ArrayBuffer.empty
  override def sleep(d: Duration): Unit =
    waits += d
    clock.advance(d)

/** What a scripted URL answers. */
enum Reply:
  case Body(bytes: Array[Byte])
  case Fail(failure: TransportFailure)

object Reply:
  def status(code: Int): Reply = Fail(TransportFailure.HttpStatus(code))

/** One call the transport saw. */
final case class Call(url: String, maxBytes: Long, deadline: Instant, accept: Option[String], at: Instant)

/** A transport that answers from a script and records every call. A URL's replies are used in order, the last one
  * repeating; a URL with no script is a 404. Each call costs `cost` on the manual clock, so a test can run a budget
  * out. It is `AutoCloseable` so a test can see that the resolver closes what it opened.
  */
final class FakeTransport(clock: ManualClock, cost: Duration = Duration.ZERO)
    extends ReleaseTransport
    with AutoCloseable:
  private val script = mutable.Map.empty[String, List[Reply]]
  val calls: mutable.ArrayBuffer[Call] = mutable.ArrayBuffer.empty
  var closed: Int = 0

  def on(url: String, replies: Reply*): FakeTransport =
    script(url) = replies.toList
    this

  override def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String]
  ): Either[TransportFailure, FetchedBody] =
    calls += Call(url.toString, maxBytes, deadline, accept, clock.instant())
    clock.advance(cost)
    val reply = script.get(url.toString) match
      case Some(first :: rest) =>
        if rest.nonEmpty then script(url.toString) = rest
        first
      case _ => Reply.status(404)
    reply match
      case Reply.Body(bytes) => Right(FetchedBody(url, 200, bytes))
      case Reply.Fail(f)     => Left(f)

  override def close(): Unit = closed += 1

object EphemeryUrls:
  val Base = "https://github.com/ephemery-testnet/ephemery-genesis"
  def asset(iteration: Int, base: String = Base): String =
    s"$base/releases/download/ephemery-$iteration/testnet-all.tar.gz"
  val ApiLatest = "https://api.github.com/repos/ephemery-testnet/ephemery-genesis/releases/latest"
  def latestJson(tag: String): Reply = Reply.Body(s"""{"tag_name":"$tag","name":"$tag"}""".getBytes("UTF-8"))
