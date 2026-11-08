package com.chipprbots.ethereum.blockchain.sync.regular

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
  * [[com.chipprbots.ethereum.ledger.BlockImportGate]], which block execution takes inside it. Not shared with the
  * Engine API: a regular-sync batch holds it for seconds, past a consensus layer's `newPayload` timeout (beacon).
  */
final class ImportLock private (mutex: Mutex[IO]):

  @volatile private var holder: Option[String] = None

  /** `io`, run holding the lock, which is released however `io` ends. */
  def guard[A](io: IO[A]): IO[A] = mutex.lock.surround(io)

  /** [[guard]] for a holder that is not an import (the chain-weight repair, #1654): while it holds the lock, [[heldBy]]
    * names it, so an importer waiting for the lock says what it waits for.
    */
  def guardAs[A](who: String)(io: IO[A]): IO[A] =
    mutex.lock.surround(IO { holder = Some(who) } >> io.guarantee(IO { holder = None }))

  /** Who holds the lock, when it is held by [[guardAs]]; `None` for an import. */
  def heldBy: Option[String] = holder

object ImportLock:
  def apply(): ImportLock = new ImportLock(Mutex.in[SyncIO, IO].unsafeRunSync())
