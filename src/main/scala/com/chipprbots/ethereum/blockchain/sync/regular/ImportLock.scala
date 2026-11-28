package com.chipprbots.ethereum.blockchain.sync.regular

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.SyncIO
import cats.effect.std.Mutex

/** Regular sync's block-import lock: ONE per node. SyncController creates it and hands it to every RegularSync it
  * starts, which hands it to every BlockImporter it spawns (#1705).
  *
  * An importer holds it for the whole of each import, branch resolution included, and takes it before it reads the
  * block it starts from. Stopping an importer does not stop an import it has running (the import is detached IO), and
  * neither does stopping a RegularSync. Without the lock, the importer that replaced a stopped one could resolve a
  * batch against a head the old import had not committed yet (#1432), then import alongside it. go-ethereum's
  * downloader waits for its importer before a new sync cycle for the same reason (`spawnSync` → `Cancel()` →
  * `cancelWg.Wait()`).
  *
  * Lock order: always OUTERMOST. Never taken while holding [[com.chipprbots.ethereum.ledger.ChainInsertLock]] or
  * [[com.chipprbots.ethereum.ledger.BlockImportGate]], which block execution takes inside it, nor while holding
  * [[com.chipprbots.ethereum.domain.CanonicalHeadLock]], which an import and a SYNC-FORK rewind take inside it. Not
  * shared with the Engine API: a regular-sync batch holds it for seconds, past a consensus layer's `newPayload` timeout
  * (beacon). The Engine API's head writes, the fork choice's among them, are serialised with an import's by
  * [[com.chipprbots.ethereum.domain.CanonicalHeadLock]] instead, whose holds are bounded (B-511, B-011).
  *
  * Regular sync's SYNC-FORK rewinds and the branch resolver's discard take it too (B-012): they move the head down, and
  * an import weighs its batch against the head under this lock before it commits.
  */
final class ImportLock private (mutex: Mutex[IO]):

  @volatile private var holder: Option[String] = None

  private val waiters = new AtomicInteger(0)

  /** `io`, run holding the lock, which is released however `io` ends. */
  def guard[A](io: IO[A]): IO[A] = locked(io)

  /** [[guard]] for a holder that is not an import (the chain-weight repair, #1654; a SYNC-FORK rewind, B-012): while it
    * holds the lock, [[heldBy]] names it, so an importer waiting for the lock says what it waits for.
    */
  def guardAs[A](who: String)(io: IO[A]): IO[A] =
    locked(IO { holder = Some(who) } >> io.guarantee(IO { holder = None }))

  /** Who holds the lock, when it is held by [[guardAs]]; `None` for an import. */
  def heldBy: Option[String] = holder

  /** How many callers wait for the lock right now: an estimate, for a metric or for a test that must know a writer is
    * held back (as [[com.chipprbots.ethereum.db.storage.pathstate.StateLock.waiting]]).
    */
  def waiting: Int = waiters.get

  // A caller is counted from its ask until it has the lock, or until it ends without it (cancelled while it waited).
  private def locked[A](io: IO[A]): IO[A] =
    IO.defer {
      val waits = new AtomicBoolean(true)
      waiters.incrementAndGet()
      val had = IO(if waits.getAndSet(false) then waiters.decrementAndGet(): Unit)
      mutex.lock.surround(had >> io).guarantee(had)
    }

object ImportLock:
  def apply(): ImportLock = new ImportLock(Mutex.in[SyncIO, IO].unsafeRunSync())
