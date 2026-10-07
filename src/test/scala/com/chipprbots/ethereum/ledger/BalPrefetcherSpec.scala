package com.chipprbots.ethereum.ledger

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.CachingMptStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.testing.Tags.*

/** The BAL prefetch ([[BalPrefetcher]]): it warms the caches execution reads through, and nothing it is given, however
  * wrong, reaches execution as anything but IO.
  */
class BalPrefetcherSpec extends AnyFlatSpec with Matchers:

  final private class Fixture:
    val source: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(source)
    val mpt: SerializingMptStorage = StateStorage.mptStorageFromNodeStorage(nodeStorage)
    val evm = new EvmCodeStorage(source)
    val cache: DecodedNodeCache = DecodedNodeCache.withOwnBudget(64L * 1024 * 1024)

    /** `contracts` accounts with code and `slots` storage slots each, plus one EOA; the state is persisted. */
    val contracts = 6
    val slots = 5
    val codes: Seq[ByteString] = (0 until contracts).map(i => ByteString(Array.fill(300 + i)((i + 1).toByte)))
    val addresses: Seq[Address] = (0 until contracts).map(i => Address(BigInt(0x1000 + i)))
    val eoa: Address = Address(BigInt(0x9999))

    val root: ByteString =
      var w = InMemoryWorldStateProxy(
        evm,
        mpt,
        _ => None,
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = true,
        ethCompatibleStorage = true
      )
      addresses.zip(codes).foreach { case (a, code) =>
        w = w.saveAccount(a, Account(UInt256.One, UInt256(5))).saveCode(a, code)
        val st = (1 to slots).foldLeft(w.getStorage(a))((s, i) => s.store(i, BigInt(i * 7)))
        w = w.saveStorage(a, st)
      }
      w = w.saveAccount(eoa, Account(UInt256.Zero, UInt256(100)))
      InMemoryWorldStateProxy.persistState(w).stateRootHash

    def bal: BlockAccessList =
      BlockAccessList(
        (addresses :+ eoa)
          .sortBy(_.bytes.toArray.toSeq.map(_ & 0xff))(
            Ordering.Implicits.seqOrdering[Seq, Int]
          )
          .map(a => AccountChanges(a, Nil, (1 to slots).map(i => UInt256(i)), Nil, Nil, Nil))
      )

    def start(
        list: BlockAccessList,
        committed: Option[ByteString],
        reader: MptStorage = mpt,
        loaded: ByteString => Unit = _ => ()
    ): BalPrefetcher.Run =
      BalPrefetcher
        .start(
          Some(list),
          committed,
          BigInt(60000000),
          1,
          root,
          Some(new PrefetchNodeReader(reader, Some(cache), 64L * 1024 * 1024, loaded)),
          evm,
          ethCompatibleStorage = true,
          new BalPrefetcher.Run.NodeKeys
        )
        .get

  /** Counts the node reads that reach `inner`. */
  final private class CountingStorage(inner: MptStorage) extends MptStorage:
    val reads = new AtomicInteger
    override def get(nodeId: Array[Byte]): MptNode =
      reads.incrementAndGet()
      inner.get(nodeId)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      inner.updateNodesInStorage(newRoot, toRemove)
    override def persist(): Unit = inner.persist()

  "BalPrefetcher" should "leave execution a cache hit on every account, slot node and code it was told about" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val f = new Fixture
    val run = f.start(f.bal, Some(f.bal.hash))
    run.awaitIdle(30000) shouldBe true
    val stats = run.finish()
    stats.errors shouldBe 0
    stats.accounts shouldBe f.contracts + 1
    stats.slots shouldBe f.contracts * f.slots
    stats.codes shouldBe f.contracts
    stats.nodes should be > 0L

    // The execution's own walk (a CachingMptStorage on the same cache) now never reaches the database.
    val counting = new CountingStorage(f.mpt)
    val exec = new CachingMptStorage(counting, f.cache)
    val trie = MerklePatriciaTrie[Address, Account](f.root.toArray, exec)(
      Address.hashedAddressEncoder,
      Account.accountSerializer
    )
    (f.addresses :+ f.eoa).foreach(a => trie.get(a) shouldBe defined)
    counting.reads.get shouldBe 0

    // And the code is in the execution code cache: a second prefetch of it finds it already there.
    f.codes.foreach { code =>
      f.evm.prefetchForExecution(ByteString(kec256(code.toArray)), _ => true) shouldBe
        EvmCodeStorage.PrefetchOutcome.AlreadyCached
    }
  }

  it should "ignore bogus entries: unknown accounts, absent slots and a codeHash with no code" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val f = new Fixture
    val ghost = Address(BigInt(0xdead))
    val noCode = Address(BigInt(0xbeef))
    val withMissingCode = InMemoryWorldStateProxy(
      f.evm,
      f.mpt,
      _ => None,
      UInt256.Zero,
      f.root,
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    ).saveAccount(
      noCode,
      Account(UInt256.One, UInt256.Zero, Account.EmptyStorageRootHash, CodeHash(ByteString(kec256(Array[Byte](9, 9)))))
    )
    val root2 = InMemoryWorldStateProxy.persistState(withMissingCode).stateRootHash
    val list = BlockAccessList(
      Seq(
        AccountChanges(ghost, Nil, Seq(UInt256(1), UInt256(2)), Nil, Nil, Nil),
        AccountChanges(noCode, Nil, Seq(UInt256(1)), Nil, Nil, Nil),
        AccountChanges(f.addresses.head, Nil, Seq(UInt256(999)), Nil, Nil, Nil)
      ).sortBy(_.address.bytes.toArray.toSeq.map(_ & 0xff))(Ordering.Implicits.seqOrdering[Seq, Int])
    )
    val run = BalPrefetcher
      .start(
        Some(list),
        Some(list.hash),
        BigInt(60000000),
        1,
        root2,
        Some(new PrefetchNodeReader(f.mpt, Some(f.cache), 1L << 26)),
        f.evm,
        true,
        new BalPrefetcher.Run.NodeKeys
      )
      .get
    run.awaitIdle(30000) shouldBe true
    val stats = run.finish()
    stats.accounts shouldBe 3
    stats.errors should be >= 1L // the code that does not exist
  }

  it should "use nothing from a list that is not the one the header commits to" taggedAs (UnitTest, StateTest) in {
    val f = new Fixture
    val run = f.start(f.bal, Some(ByteString(Array.fill(32)(1.toByte))))
    run.awaitIdle(30000) shouldBe true
    val stats = run.finish()
    (stats.accounts, stats.slots, stats.codes, stats.nodes) shouldBe ((0L, 0L, 0L, 0L))
    f.cache.entries shouldBe 0
  }

  it should "survive a missing node and leave the miss to execution" taggedAs (UnitTest, StateTest) in {
    val f = new Fixture
    // Remove the account-trie root: every walk fails at its first step.
    f.nodeStorage.update(Seq(f.root), Nil)
    val run = f.start(f.bal, Some(f.bal.hash))
    run.awaitIdle(30000) shouldBe true
    val stats = run.finish()
    stats.errors should be >= (f.contracts + 1).toLong
    stats.codes shouldBe 0L
  }

  it should "cancel: finish returns at once, drains, and nothing runs afterwards" taggedAs (UnitTest, StateTest) in {
    val f = new Fixture
    val many = BlockAccessList(
      (0 until 4000)
        .map(i => AccountChanges(Address(BigInt(0x100000 + i)), Nil, (1 to 3).map(UInt256(_)), Nil, Nil, Nil))
        .sortBy(_.address.bytes.toArray.toSeq.map(_ & 0xff))(Ordering.Implicits.seqOrdering[Seq, Int])
    )
    val run = BalPrefetcher
      .start(
        Some(many),
        Some(many.hash),
        BigInt(60000000),
        1,
        f.root,
        Some(new PrefetchNodeReader(f.mpt, Some(f.cache), 1L << 26)),
        f.evm,
        true,
        new BalPrefetcher.Run.NodeKeys
      )
      .get
    val first = run.finish()
    val second = run.finish()
    second.accounts shouldBe first.accounts
    second.nodes shouldBe first.nodes
    first.accounts should be <= 4000L
  }

  it should "stop issuing state reads once its share of the node cache is spent" taggedAs (UnitTest, StateTest) in {
    val f = new Fixture
    val tiny = new PrefetchNodeReader(f.mpt, Some(f.cache), 1L)
    val run = BalPrefetcher
      .start(
        Some(f.bal),
        Some(f.bal.hash),
        BigInt(60000000),
        1,
        f.root,
        Some(tiny),
        f.evm,
        true,
        new BalPrefetcher.Run.NodeKeys
      )
      .get
    run.awaitIdle(30000) shouldBe true
    val stats = run.finish()
    stats.stoppedEarly shouldBe true
    f.cache.entries should be <= 1
  }
