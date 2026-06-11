package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags._

/** Equivalence test for the combined single-pass recovery scan: one walk that checks BOTH bytecode and storage per
  * account must find exactly the gaps the two legacy walks (BytecodeRecoveryActor + StorageRecoveryActor) would — no
  * misses, no spurious, no double-counts. Built against a real account trie covering every present/missing combination.
  */
class CombinedRecoveryScanSpec extends AnyFunSuite {

  test("combined scan finds exactly the missing bytecodes and storage tries in one pass", UnitTest, SyncTest) {
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val mpt = stateStorage.getBackingStorage(0)
    val evm = new EvmCodeStorage(ds)

    // present bytecode: store the code; codeHash = keccak(code) (non-empty)
    def presentCode(seed: Byte): ByteString = {
      val code = Array.fill[Byte](8)(seed)
      val h = ByteString(kec256(code))
      evm.put(h, ByteString(code)).commit()
      h
    }
    // a codeHash that was never stored (and isn't the empty-code hash)
    def missingCodeHash(seed: Byte): ByteString = ByteString(Array.fill[Byte](32)((seed ^ 0x5a).toByte))
    // present storage: build a 2-slot storage trie in mpt; its root node is stored (non-empty)
    def presentStorageRoot(seed: Byte): ByteString = {
      val t = MerklePatriciaTrie[Array[Byte], Array[Byte]](mpt)
        .put(Array[Byte](seed, 0x01), Array[Byte](seed, 0x11))
        .put(Array[Byte](seed, 0x02), Array[Byte](seed, 0x22))
      ByteString(t.getRootHash)
    }
    // a non-empty storageRoot that was never stored
    def missingStorageRoot(seed: Byte): ByteString = ByteString(Array.fill[Byte](32)((seed ^ 0x3c).toByte))
    def acctHash(i: Int): ByteString = ByteString(Array.fill[Byte](32)(i.toByte))

    val a2Code = missingCodeHash(2)
    val a3Stor = missingStorageRoot(3)
    val a4Stor = missingStorageRoot(4)
    val a5Code = missingCodeHash(5)

    val accounts: Seq[(ByteString, Account)] = Seq(
      acctHash(1) -> Account(nonce = UInt256.Zero, storageRoot = presentStorageRoot(1), codeHash = presentCode(1)),
      acctHash(2) -> Account(nonce = UInt256.Zero, storageRoot = Account.EmptyStorageRootHash, codeHash = a2Code),
      acctHash(3) -> Account(nonce = UInt256.Zero, storageRoot = a3Stor, codeHash = Account.EmptyCodeHash),
      acctHash(4) -> Account(nonce = UInt256.Zero, storageRoot = a4Stor, codeHash = presentCode(4)),
      acctHash(5) -> Account(nonce = UInt256.Zero, storageRoot = presentStorageRoot(5), codeHash = a5Code),
      acctHash(6) -> Account(
        nonce = UInt256.Zero,
        storageRoot = presentStorageRoot(6),
        codeHash = Account.EmptyCodeHash
      ),
      acctHash(7) -> Account.empty()
    )

    val stateRoot = ByteString(
      accounts
        .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](mpt)) { case (t, (h, acc)) =>
          t.put(h.toArray, acc.toBytes)
        }
        .getRootHash
    )

    val scan = new CombinedRecoveryScan(mpt, evm)
    scan.scanFrom(stateRoot)

    assert(
      scan.missingBytecodes.toSet == Set(a2Code, a5Code),
      s"bytecode gaps mismatch: ${scan.missingBytecodes.map(_.take(2))}"
    )
    assert(scan.missingBytecodes.size == 2, s"unexpected bytecode-gap count: ${scan.missingBytecodes.size}")
    assert(
      scan.missingStorageTries.toSet == Set((acctHash(3), a3Stor), (acctHash(4), a4Stor)),
      s"storage gaps mismatch: ${scan.missingStorageTries.map { case (a, s) => (a.take(2), s.take(2)) }}"
    )
    assert(scan.missingStorageTries.size == 2, s"unexpected storage-gap count: ${scan.missingStorageTries.size}")
  }

  test("combined scan reports no gaps when the state is complete", UnitTest, SyncTest) {
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val mpt = stateStorage.getBackingStorage(0)
    val evm = new EvmCodeStorage(ds)

    val code = Array.fill[Byte](8)(0x7d)
    val codeHash = ByteString(kec256(code))
    evm.put(codeHash, ByteString(code)).commit()
    val storageRoot = ByteString(
      MerklePatriciaTrie[Array[Byte], Array[Byte]](mpt).put(Array[Byte](1, 2), Array[Byte](3, 4)).getRootHash
    )

    val stateRoot = ByteString(
      MerklePatriciaTrie[Array[Byte], Array[Byte]](mpt)
        .put(
          Array.fill[Byte](32)(0xaa.toByte),
          Account(nonce = UInt256.Zero, storageRoot = storageRoot, codeHash = codeHash).toBytes
        )
        .put(Array.fill[Byte](32)(0xbb.toByte), Account.empty().toBytes)
        .getRootHash
    )

    val scan = new CombinedRecoveryScan(mpt, evm)
    scan.scanFrom(stateRoot)
    assert(scan.missingBytecodes.isEmpty, s"expected no bytecode gaps, got ${scan.missingBytecodes.size}")
    assert(scan.missingStorageTries.isEmpty, s"expected no storage gaps, got ${scan.missingStorageTries.size}")
  }
}
