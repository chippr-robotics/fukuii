package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.db.dataSource.DataSource.Key
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace
import com.chipprbots.ethereum.db.dataSource.DataSource.Value
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.testing.Tags.*

trait DataSourceTestBehavior extends ScalaCheckPropertyChecks with ObjectGenerators:
  this: AnyFlatSpec =>

  val KeySizeWithoutPrefix: Int = 32
  val OtherNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('r'.toByte)

  def prepareUpdate(
      namespace: Namespace = OtherNamespace,
      toRemove: Seq[Key] = Nil,
      toUpsert: Seq[(Key, Value)] = Nil
  ): Seq[DataSourceUpdate] =
    Seq(DataSourceUpdate(namespace, toRemove, toUpsert))

  def withDir(testCode: String => Any): Unit =
    val path = Files.createTempDirectory("testdb").getFileName.toString
    try testCode(path)
    finally
      val dir = new File(path)
      assert(!dir.exists() || dir.delete(), "File deletion failed")

  // scalastyle:off
  def dataSource(createDataSource: => String => DataSource): Unit =
    it should "be able to insert and retrieve stored keys" taggedAs (UnitTest, DatabaseTest) in {
      val someByteString = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      withDir { path =>
        val dataSource = createDataSource(path)
        dataSource.update(prepareUpdate(toUpsert = Seq(someByteString -> someByteString)))

        dataSource.get(OtherNamespace, someByteString) match
          case Some(b) if b == someByteString => succeed
          case _                              => fail()

        dataSource.destroy()
      }
    }

    it should "throw an exception if the rocksdb storage is unavailable" taggedAs (UnitTest, DatabaseTest) in {
      withDir { path =>
        val dataSource = createDataSource(path)
        val someByteString = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
        dataSource.destroy()
        assertThrows[RocksDbDataSourceClosedException](
          dataSource.update(prepareUpdate(toUpsert = Seq(someByteString -> someByteString)))
        )
      }
    }

    it should "allow to remove keys" taggedAs (UnitTest, DatabaseTest) in {
      val key1 = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      val key2 = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      withDir { path =>
        val dataSource = createDataSource(path)

        dataSource.update(prepareUpdate(toUpsert = Seq(key1 -> key1, key2 -> key2)))

        assert(dataSource.get(OtherNamespace, key1).isDefined)
        assert(dataSource.get(OtherNamespace, key2).isDefined)

        dataSource.update(prepareUpdate(toRemove = Seq(key1)))

        assert(dataSource.get(OtherNamespace, key1).isEmpty)
        assert(dataSource.get(OtherNamespace, key2).isDefined)

        dataSource.destroy()
      }
    }

    it should "remove all keys after clear" in {
      val someByteString = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      withDir { path =>
        val dataSource = createDataSource(path)

        dataSource.update(prepareUpdate(toUpsert = Seq(someByteString -> someByteString)))

        assert(dataSource.get(OtherNamespace, someByteString).isDefined)

        dataSource.clear()

        assert(dataSource.get(OtherNamespace, someByteString).isEmpty)

        dataSource.destroy()
      }
    }

    it should "allow using multiple namespaces with the same key" in {
      val OtherNamespace2: IndexedSeq[Byte] = IndexedSeq[Byte]('h'.toByte)
      val someByteString = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      val someValue1 = 1.toByte +: someByteString
      val someValue2 = 2.toByte +: someByteString
      withDir { path =>
        val dataSource = createDataSource(path)

        // Insertion
        dataSource.update(prepareUpdate(namespace = OtherNamespace, toUpsert = Seq(someByteString -> someValue1)))
        dataSource.update(prepareUpdate(namespace = OtherNamespace2, toUpsert = Seq(someByteString -> someValue2)))

        assert(dataSource.get(OtherNamespace, someByteString).contains(someValue1))
        assert(dataSource.get(OtherNamespace2, someByteString).contains(someValue2))

        // Removal
        dataSource.update(prepareUpdate(namespace = OtherNamespace2, toRemove = Seq(someByteString)))

        assert(dataSource.get(OtherNamespace, someByteString).contains(someValue1))
        assert(dataSource.get(OtherNamespace2, someByteString).isEmpty)

        dataSource.destroy()
      }
    }

  /** One `update` call is one write batch (RocksDB: one `WriteBatch`; spec 022 FR-008 relies on it for a heal write and
    * its dangling-position deletes). Within it the removals of an update are applied before its upserts, and updates in
    * order: a delete and a put of the same key leave the put. An update spanning two namespaces changes both.
    * `rejectsUnknownNamespace` (RocksDB: a namespace is a column family) adds the failure half: an update that cannot
    * be built changes nothing, not even the namespace before the bad one.
    */
  def batchUpdate(createDataSource: => String => DataSource, rejectsUnknownNamespace: Boolean): Unit =
    val NamespaceA: IndexedSeq[Byte] = IndexedSeq[Byte]('r'.toByte)
    val NamespaceB: IndexedSeq[Byte] = IndexedSeq[Byte]('h'.toByte)

    it should "keep the put when one update call deletes and puts the same key" taggedAs (UnitTest, DatabaseTest) in {
      val key = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      val (old, value) = (1.toByte +: key, 2.toByte +: key)
      withDir { path =>
        val dataSource = createDataSource(path)
        dataSource.update(prepareUpdate(namespace = NamespaceA, toUpsert = Seq(key -> old)))

        // remove and upsert of the same key inside one update
        dataSource.update(prepareUpdate(namespace = NamespaceA, toRemove = Seq(key), toUpsert = Seq(key -> value)))
        assert(dataSource.get(NamespaceA, key).contains(value))

        // a removal update, then an upsert update, in one call (the order PathNodeStorage.writeNodeWithDeletes uses)
        dataSource.update(
          prepareUpdate(namespace = NamespaceA, toRemove = Seq(key)) ++
            prepareUpdate(namespace = NamespaceA, toUpsert = Seq(key -> old))
        )
        assert(dataSource.get(NamespaceA, key).contains(old))
        dataSource.destroy()
      }
    }

    it should "change two namespaces in one update call" taggedAs (UnitTest, DatabaseTest) in {
      val key = byteStringOfLengthNGen(KeySizeWithoutPrefix).sample.get
      withDir { path =>
        val dataSource = createDataSource(path)
        dataSource.update(prepareUpdate(namespace = NamespaceB, toUpsert = Seq(key -> key)))

        dataSource.update(
          prepareUpdate(namespace = NamespaceB, toRemove = Seq(key)) ++
            prepareUpdate(namespace = NamespaceA, toUpsert = Seq(key -> key))
        )
        assert(dataSource.get(NamespaceA, key).contains(key))
        assert(dataSource.get(NamespaceB, key).isEmpty)

        if rejectsUnknownNamespace then
          val unknown = IndexedSeq[Byte]('~'.toByte)
          val other = 3.toByte +: key
          assertThrows[Exception](
            dataSource.update(
              prepareUpdate(namespace = NamespaceA, toUpsert = Seq(key -> other)) ++
                prepareUpdate(namespace = unknown, toUpsert = Seq(key -> other))
            )
          )
          assert(dataSource.get(NamespaceA, key).contains(key)) // the failed batch changed nothing
        dataSource.destroy()
      }
    }

  // scalastyle:on
