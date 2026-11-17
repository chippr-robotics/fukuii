package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import com.chipprbots.ethereum.db.dataSource.DataSource.*
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces

/** What a spec needs to stand inside a read of a changeset record: the state of a block behind the one the disk holds
  * is made from those records, by whoever asks for it.
  */
object RecordReads:

  /** An in-memory source that tells of every read of a changeset record. (A subclass and not a wrapper: the storages
    * ask a source whether it is the in-memory one.)
    */
  final class Watched extends EphemDataSource(Map.empty):
    @volatile var onRecordRead: () => Unit = () => ()
    override def get(namespace: Namespace, key: Key): Option[Value] =
      if namespace == Namespaces.StateChangesetNamespace then onRecordRead()
      super.get(namespace, key)

  /** Stops the threads whose name starts with `named` inside their first read of a record of `source`, until they are
    * let go: no sleep, and the same interleaving every run.
    */
  class Stopped(source: Watched, named: String, threads: Int):
    private val inTheRead = new CountDownLatch(threads)
    private val letGo = new CountDownLatch(1)
    private val stopped = ConcurrentHashMap.newKeySet[String]()
    private val reads = new ConcurrentHashMap[String, AtomicInteger]()
    source.onRecordRead = () =>
      val name = Thread.currentThread.getName
      reads.computeIfAbsent(name, _ => new AtomicInteger(0)).incrementAndGet()
      if name.startsWith(named) && stopped.add(name) then
        inTheRead.countDown()
        letGo.await(60, TimeUnit.SECONDS)

    /** How many records the thread of that name read. */
    def readsOf(name: String): Int = Option(reads.get(name)).fold(0)(_.get)

    /** Waits until every one of them is in its read. */
    def allStopped(): Unit =
      if !inTheRead.await(60, TimeUnit.SECONDS) then
        throw new IllegalStateException(s"not every one of the $threads threads '$named' came to read a record")

    def go(): Unit = letGo.countDown()

    /** Lets them go, and tells of no more reads. */
    def over(): Unit =
      go()
      source.onRecordRead = () => ()

  /** `body` on a thread of its own, named `name`. */
  final class Running[A](name: String, body: => A):
    private val result = new AtomicReference[Option[Try[A]]](None)
    val thread = new Thread(() => result.set(Some(Try(body))), name)
    thread.setDaemon(true)
    thread.start()

    /** What it gave, or threw, once it has ended. */
    def ended: Try[A] =
      thread.join(60000)
      result.get.getOrElse(throw new IllegalStateException(s"'$name' has not ended"))

  def onThread[A](name: String)(body: => A): Running[A] = new Running(name, body)
