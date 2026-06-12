package com.chipprbots.ethereum.db.dataSource

import java.nio.ByteBuffer

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource._
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

class EphemDataSource(var storage: Map[ByteBuffer, Array[Byte]]) extends DataSource {

  /** key.drop to remove namespace prefix from the key
    * @return
    *   key values paris from this storage
    */
  def getAll(namespace: Namespace): Seq[(IndexedSeq[Byte], IndexedSeq[Byte])] = synchronized {
    storage.toSeq.map { case (key, value) => (key.array().drop(namespace.length).toIndexedSeq, value.toIndexedSeq) }
  }

  override def get(namespace: Namespace, key: Key): Option[Value] =
    storage.get(ByteBuffer.wrap((namespace ++ key).toArray)).map(_.toIndexedSeq)

  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    get(namespace, key.toIndexedSeq).map(_.toArray)

  override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    synchronized {
      def cmp(a: Array[Byte], b: Array[Byte]): Int = {
        val n = math.min(a.length, b.length)
        var i = 0
        var d = 0
        while (i < n && d == 0) {
          d = (a(i) & 0xff) - (b(i) & 0xff)
          i += 1
        }
        if (d != 0) d else a.length - b.length
      }
      val ns = namespace.toArray
      storage = storage.filter { case (k, _) =>
        val raw = k.array()
        val inNamespace = raw.length >= ns.length && raw.take(ns.length).sameElements(ns)
        if (!inNamespace) true
        else {
          val suffix = raw.drop(ns.length)
          !(cmp(suffix, fromKey) >= 0 && cmp(suffix, toKeyExclusive) < 0)
        }
      }
    }

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = synchronized {
    dataSourceUpdates.foreach {
      case DataSourceUpdate(namespace, toRemove, toUpsert) =>
        update(namespace, toRemove, toUpsert)
      case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
        updateOptimized(namespace, toRemove, toUpsert)
    }
  }

  private def update(namespace: Namespace, toRemove: Seq[Key], toUpsert: Seq[(Key, Value)]): Unit = synchronized {
    val afterRemoval =
      toRemove.foldLeft(storage)((storage, key) => storage - ByteBuffer.wrap((namespace ++ key).toArray))
    val afterUpdate = toUpsert.foldLeft(afterRemoval)((storage, toUpdate) =>
      storage + (ByteBuffer.wrap((namespace ++ toUpdate._1).toArray) -> toUpdate._2.toArray)
    )
    storage = afterUpdate
  }

  private def updateOptimized(
      namespace: Namespace,
      toRemove: Seq[Array[Byte]],
      toUpsert: Seq[(Array[Byte], Array[Byte])]
  ): Unit = synchronized {
    update(namespace, toRemove.map(_.toIndexedSeq), toUpsert.map(s => (s._1.toIndexedSeq, s._2.toIndexedSeq)))
  }

  override def clear(): Unit = synchronized {
    storage = Map()
  }

  override def close(): Unit = ()

  override def destroy(): Unit = ()

  override def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    Stream.emits(storage.toList.map { case (key, value) => Right((key.array(), value)) })

  override def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = {
    val namespaceVals = storage.collect {
      case (buffer, bytes) if buffer.array().startsWith(namespace) => Right((buffer.array(), bytes))
    }

    Stream.emits(namespaceVals.toSeq)
  }
}

object EphemDataSource {
  def apply(): EphemDataSource = new EphemDataSource(Map())
}
