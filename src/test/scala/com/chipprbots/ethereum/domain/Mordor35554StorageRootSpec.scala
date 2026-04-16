package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage

class Mordor35554StorageRootSpec extends AnyFunSuite {

  test("EthereumUInt256Mpt storage root matches Core-Geth for Mordor block 35554") {
    // From Core-Geth (Mordor) eth_getProof at block 0x8ae2 for contract 0x2fae8a...fdb2e:
    // storageHash = 0x85da94d7030332ab4c359e9959b4ac1b3ae6c2eac781e7730f11d9f2837ece8d
    // storage[0x0] = 0xf7f04e1052c6a30f651b07bb8f6bedf4844137f5
    val expectedStorageRoot = Hex.decode("85da94d7030332ab4c359e9959b4ac1b3ae6c2eac781e7730f11d9f2837ece8d")

    val key: BigInt = BigInt(0)
    val value: BigInt = BigInt("f7f04e1052c6a30f651b07bb8f6bedf4844137f5", 16)

    val dataSource = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(dataSource)
    val mptStorage = stateStorage.getBackingStorage(0)

    val storageTrie = EthereumUInt256Mpt.storageMpt(Account.EmptyStorageRootHash, mptStorage)
    val updated = storageTrie.put(key, value)

    assert(updated.getRootHash.sameElements(expectedStorageRoot))
  }
}
