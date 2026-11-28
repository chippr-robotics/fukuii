package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.StateOverride
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

/** The four request contracts of an Amsterdam block (EIP-7002 and EIP-7251 queues, EIP-8282 builder deposit and exit
  * queues), each a contract that does nothing, as state overrides or as a base state.
  *
  * A simulated Prague, Osaka or Amsterdam block runs the EIP-7685 request system calls (B-163; Prague and Osaka call
  * the first two), and go-ethereum fails one whose contract has no code (`failed to process <queue>: empty system
  * contract: no code at <address>`, which fukuii answers since #1727 PR 5). A real chain has the contracts; a test
  * world built on an empty state does not, so a test of such a block puts these in. STOP returns no data, so the block
  * has no request; on Amsterdam its only effect on the access list is a read of each address at index `n + 1`.
  */
object RequestContractStubs:

  val Addresses: Seq[Address] = Seq(
    BlockExecution.WithdrawalQueueAddress,
    BlockExecution.ConsolidationQueueAddress,
    BlockExecution.BuilderDepositQueueAddress,
    BlockExecution.BuilderExitQueueAddress
  )

  /** STOP. */
  val Stop: ByteString = ByteString(Array[Byte](0x00))

  val overrides: Map[Address, StateOverride] = Addresses.map(_ -> StateOverride(code = Some(Stop))).toMap

  /** A state holding only the four contracts, written to `mptStorage` and `evmCodeStorage`; its root. The base state of
    * a test chain whose simulated blocks call the request contracts, as a real chain's base state is.
    */
  def deployedRoot(evmCodeStorage: EvmCodeStorage, mptStorage: MptStorage): ByteString =
    val empty = InMemoryWorldStateProxy(
      evmCodeStorage,
      mptStorage,
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    )
    val world = Addresses.foldLeft(empty) { (w, address) =>
      w.saveAccount(address, Account(nonce = UInt256(1), codeHash = CodeHash(kec256(Stop)))).saveCode(address, Stop)
    }
    InMemoryWorldStateProxy.persistStateAndWriteCode(world).stateRootHash
