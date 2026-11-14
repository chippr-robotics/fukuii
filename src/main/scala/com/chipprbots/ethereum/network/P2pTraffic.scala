package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.LongAdder

/** What the node's RLPx connections carried, counted in process for the `--tui` dashboard's System view (spec 029
  * FR-044): bytes as the socket carried them (after framing and encryption, handshake included, TCP and IP headers
  * not), and messages decoded and encoded.
  *
  * It observes and decides nothing. Every method is one add to a counter that many connections add to at once and none
  * waits on: it takes no lock, sends nothing, and cannot throw, so a connection's order, acknowledgements and timing
  * are what they are without it. Discovery's UDP traffic is not counted.
  *
  * A plain class with no registry behind it: making one reads no configuration and registers nothing.
  */
final class P2pTraffic:

  private val in = new LongAdder
  private val out = new LongAdder
  private val read = new LongAdder
  private val written = new LongAdder

  /** A TCP read of `bytes`, as it came off the socket. */
  def received(bytes: Int): Unit = if bytes > 0 then in.add(bytes.toLong)

  /** A write of `bytes` handed to the socket. Counted when it is handed over: one that then fails was counted. */
  def sent(bytes: Int): Unit = if bytes > 0 then out.add(bytes.toLong)

  /** A message was decoded from a peer: its Hello, or the Disconnect it sent in its place, included. One that could not
    * be decoded is not a message.
    */
  def messageReceived(): Unit = read.increment()

  /** A message was encoded for a peer, this node's Hello included. Counted when it is encoded: one whose write then
    * fails was counted, as its bytes were.
    */
  def messageSent(): Unit = written.increment()

  /** The totals at this instant. Each only grows; read while connections add, they are of slightly different moments.
    */
  def snapshot: P2pTraffic.Snapshot = P2pTraffic.Snapshot(in.sum(), out.sum(), read.sum(), written.sum())

object P2pTraffic:

  /** The node's own. */
  val Node: P2pTraffic = new P2pTraffic

  /** Totals since the node started. */
  final case class Snapshot(bytesIn: Long, bytesOut: Long, messagesIn: Long, messagesOut: Long)
