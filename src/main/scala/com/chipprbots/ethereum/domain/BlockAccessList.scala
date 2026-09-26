package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.ByteUtils

import BlockAccessList.*

/** EIP-7928 block-level access list (Amsterdam). ETH-family only: no ETC chain schedules Amsterdam.
  *
  * RLP, as execution-specs `forks/amsterdam/block_access_lists.py` and go-ethereum `core/types/bal` encode it:
  * {{{
  * BlockAccessList = [AccountChanges, ...]
  * AccountChanges  = [address, [SlotChanges, ...], [slot, ...], [BalanceChange, ...], [NonceChange, ...],
  *                    [CodeChange, ...]]            // storage_changes, storage_reads, balance, nonce, code
  * SlotChanges     = [slot, [StorageChange, ...]]
  * StorageChange   = [block_access_index, new_value]
  * BalanceChange   = [block_access_index, post_balance]
  * NonceChange     = [block_access_index, new_nonce]
  * CodeChange      = [block_access_index, new_code]
  * }}}
  * Every integer is minimal big-endian, zero being the empty string: `block_access_index` is a u32, `new_nonce` a u64,
  * slots, storage values and balances u256. The header commits to `keccak256(rlp(bal))` ([[hash]]).
  *
  * The encoder writes the list exactly as given; ordering it is the builder's job. [[BlockAccessList.decode]] is
  * strict: it accepts only the canonical encoding of a well-formed list, so for an accepted input the raw bytes, the
  * decoded value and its re-encoding are interchangeable, and `keccak256(bytes) == decoded.hash`.
  */
final case class BlockAccessList(accounts: Seq[AccountChanges]):

  def toRLPEncodable: RLPEncodeable = RLPList(accounts.map(accountToRLP)*)

  def toBytes: ByteString = ByteString(rlp.encode(toRLPEncodable))

  /** `keccak256(rlp(bal))`, the header's `blockAccessListHash` (execution-specs `hash_block_access_list`). */
  def hash: ByteString = ByteString(kec256(rlp.encode(toRLPEncodable)))

  /** The items the EIP-7928 size limit counts, `itemCount <= gasLimit / 2000`: one per account plus one per distinct
    * storage key of that account, reads and writes together (execution-specs `validate_block_access_list_gas_limit`).
    */
  def itemCount: Long =
    accounts.iterator.map { account =>
      1L + (account.storageChanges.iterator.map(_.slot) ++ account.storageReads.iterator).toSet.size
    }.sum

object BlockAccessList:

  /** Largest `block_access_index`: the field is a u32. */
  val MaxBlockAccessIndex: Long = 0xffffffffL

  /** Largest `new_nonce`: the field is a u64. */
  val MaxNonce: BigInt = (BigInt(1) << 64) - 1

  val Empty: BlockAccessList = BlockAccessList(Nil)

  /** `keccak256(rlp([])) = keccak256(0xc0) = 0x1dcc4de8…9347`: the commitment of a block that accesses nothing, and of
    * every genesis that activates Amsterdam (go-ethereum `types.EmptyBlockAccessListHash`).
    */
  val EmptyHash: ByteString = Empty.hash

  final case class AccountChanges(
      address: Address,
      storageChanges: Seq[SlotChanges],
      storageReads: Seq[UInt256],
      balanceChanges: Seq[BalanceChange],
      nonceChanges: Seq[NonceChange],
      codeChanges: Seq[CodeChange]
  )

  final case class SlotChanges(slot: UInt256, changes: Seq[StorageChange]):
    require(changes.nonEmpty, "EIP-7928: a SlotChanges entry holds at least one StorageChange")

  final case class StorageChange(blockAccessIndex: Long, newValue: UInt256):
    requireIndex(blockAccessIndex)

  final case class BalanceChange(blockAccessIndex: Long, postBalance: UInt256):
    requireIndex(blockAccessIndex)

  final case class NonceChange(blockAccessIndex: Long, newNonce: BigInt):
    requireIndex(blockAccessIndex)
    require(newNonce >= 0 && newNonce <= MaxNonce, s"EIP-7928: nonce $newNonce is not a u64")

  final case class CodeChange(blockAccessIndex: Long, newCode: ByteString):
    requireIndex(blockAccessIndex)

  private def requireIndex(index: Long): Unit =
    require(index >= 0 && index <= MaxBlockAccessIndex, s"EIP-7928: block access index $index is not a u32")

  // ── Encoding ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Minimal big-endian, zero as the empty string. */
  private def uintRLP(value: BigInt): RLPValue = RLPValue(ByteUtils.bigIntToUnsignedByteArray(value))

  private def indexRLP(blockAccessIndex: Long): RLPValue = uintRLP(BigInt(blockAccessIndex))

  private def accountToRLP(account: AccountChanges): RLPList =
    RLPList(
      RLPValue(account.address.toArray),
      RLPList(account.storageChanges.map { slotChanges =>
        RLPList(
          uintRLP(slotChanges.slot.toBigInt),
          RLPList(slotChanges.changes.map(c => RLPList(indexRLP(c.blockAccessIndex), uintRLP(c.newValue.toBigInt)))*)
        )
      }*),
      RLPList(account.storageReads.map(slot => uintRLP(slot.toBigInt))*),
      RLPList(account.balanceChanges.map(c => RLPList(indexRLP(c.blockAccessIndex), uintRLP(c.postBalance.toBigInt)))*),
      RLPList(account.nonceChanges.map(c => RLPList(indexRLP(c.blockAccessIndex), uintRLP(c.newNonce)))*),
      RLPList(account.codeChanges.map(c => RLPList(indexRLP(c.blockAccessIndex), RLPValue(c.newCode.toArray)))*)
    )

  // ── Strict decoding ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Decodes an RLP block access list (the `blockAccessList` of `engine_newPayloadV5`, an eth/71 BAL body), accepting
    * exactly the canonical encoding of a list that satisfies EIP-7928's context-free rules. Anything else is a `Left`
    * naming the first offending field:
    *
    *   - Shape: each struct is a list of exactly its field count; empty input is rejected (the empty list is `0xc0`);
    *     no trailing bytes, and every length prefix and single byte in minimal form (go-ethereum `rlp.DecodeBytes`).
    *   - Fields: an address is exactly 20 bytes; `block_access_index` fits a u32, a nonce a u64, a slot, storage value
    *     or balance 32 bytes; no integer has a leading zero byte ("non-canonical integer").
    *   - EIP-7928 "Ordering, Uniqueness and Determinism": accounts strictly ascending by address; `storage_changes` and
    *     `storage_reads` strictly ascending by slot compared as numbers (execution-specs sorts `U256`, go-ethereum
    *     `uint256.Cmp`; the EIP's "lexicographic by storage key" agrees over 32-byte keys, not over minimal RLP bytes);
    *     every change list strictly ascending by index; every `SlotChanges` non-empty; no slot both read and written.
    *
    * Left to block validation, because they need the block: `block_access_index <= len(transactions) + 1`, the
    * `gasLimit / 2000` bound on [[BlockAccessList.itemCount]], and go-ethereum's cap on a code change's size.
    */
  def decode(bytes: ByteString): Either[String, BlockAccessList] =
    if bytes.isEmpty then Left("empty input: the empty block access list is 0xc0, not zero bytes")
    else
      val raw = bytes.toArray
      // fukuii's generic RLP reader tolerates trailing bytes, non-minimal length prefixes and single bytes behind a
      // string header, and can throw on truncated input. The field checks below cover integers; re-encoding and
      // comparing with the input covers the framing: a strict decoding is the inverse of the canonical encoder.
      try
        decodeAccounts(rlp.rawDecode(raw)).flatMap { bal =>
          if java.util.Arrays.equals(rlp.encode(bal.toRLPEncodable), raw) then Right(bal)
          else Left("non-canonical RLP framing (trailing bytes, or a length prefix or single byte not in minimal form)")
        }
      catch
        case NonFatal(e) => Left(s"malformed RLP: ${e.getMessage}")
        // The generic reader recurses once per nesting level with no bound, and StackOverflowError is fatal, so
        // NonFatal does not catch it. A block access list nests five lists deep; anything that exhausts the stack is
        // hostile input (a peer's eth/71 body), and rejecting it is the result, not a swallowed failure. The reader is
        // pure, so unwinding leaves no state behind.
        case _: StackOverflowError => Left("malformed RLP: lists nested deeper than a block access list can be")

  private def decodeAccounts(encodeable: RLPEncodeable): Either[String, BlockAccessList] =
    for
      items <- listItems(encodeable, "block access list")
      accounts <- each(items, "accounts")(decodeAccount)
      _ <- strictlyAscending(accounts.map(_.address), "accounts", "address")((a, b) =>
        java.util.Arrays.compareUnsigned(a.toArray, b.toArray) < 0
      )
    yield BlockAccessList(accounts)

  private def decodeAccount(encodeable: RLPEncodeable, path: String): Either[String, AccountChanges] =
    for
      f <- fields(encodeable, path, 6)
      address <- decodeAddress(f(0), s"$path.address")
      storageChanges <- listItems(f(1), s"$path.storage_changes").flatMap(each(_, s"$path.storage_changes")(decodeSlot))
      _ <- strictlyAscending(storageChanges.map(_.slot), s"$path.storage_changes", "slot")(_ < _)
      storageReads <- listItems(f(2), s"$path.storage_reads").flatMap(each(_, s"$path.storage_reads")(decodeU256))
      _ <- strictlyAscending(storageReads, s"$path.storage_reads", "slot")(_ < _)
      written = storageChanges.iterator.map(_.slot).toSet
      _ <- storageReads.zipWithIndex
        .collectFirst { case (slot, i) if written.contains(slot) => s"$path.storage_reads[$i]: slot is also written" }
        .toLeft(())
      balanceChanges <- indexedChanges(f(3), s"$path.balance_changes", "post_balance")(decodeU256)
      nonceChanges <- indexedChanges(f(4), s"$path.nonce_changes", "new_nonce")(decodeU64)
      codeChanges <- indexedChanges(f(5), s"$path.code_changes", "new_code")(decodeBytes)
    yield AccountChanges(
      address,
      storageChanges,
      storageReads,
      balanceChanges.map((i, v) => BalanceChange(i, v)),
      nonceChanges.map((i, v) => NonceChange(i, v)),
      codeChanges.map((i, v) => CodeChange(i, ByteString(v)))
    )

  private def decodeSlot(encodeable: RLPEncodeable, path: String): Either[String, SlotChanges] =
    for
      f <- fields(encodeable, path, 2)
      slot <- decodeU256(f(0), s"$path.slot")
      changes <- indexedChanges(f(1), s"$path.changes", "new_value")(decodeU256)
      _ <- Either.cond(changes.nonEmpty, (), s"$path.changes: empty (a SlotChanges holds at least one StorageChange)")
    yield SlotChanges(slot, changes.map((i, v) => StorageChange(i, v)))

  /** `[[block_access_index, value], ...]`, strictly ascending by index. */
  private def indexedChanges[A](encodeable: RLPEncodeable, path: String, valueName: String)(
      decodeValue: (RLPEncodeable, String) => Either[String, A]
  ): Either[String, Seq[(Long, A)]] =
    for
      items <- listItems(encodeable, path)
      changes <- each(items, path) { (item, itemPath) =>
        for
          f <- fields(item, itemPath, 2)
          index <- decodeUint(f(0), s"$itemPath.block_access_index", maxBytes = 4)
          value <- decodeValue(f(1), s"$itemPath.$valueName")
        yield (index.toLong, value)
      }
      _ <- strictlyAscending(changes.map(_._1), path, "block_access_index")(_ < _)
    yield changes

  private def decodeAddress(encodeable: RLPEncodeable, path: String): Either[String, Address] =
    decodeBytes(encodeable, path).flatMap { b =>
      Either.cond(b.length == Address.Length, Address(b), s"$path: ${b.length} bytes, an address is ${Address.Length}")
    }

  private def decodeU256(encodeable: RLPEncodeable, path: String): Either[String, UInt256] =
    decodeUint(encodeable, path, maxBytes = 32).map(UInt256(_))

  private def decodeU64(encodeable: RLPEncodeable, path: String): Either[String, BigInt] =
    decodeUint(encodeable, path, maxBytes = 8)

  private def decodeUint(encodeable: RLPEncodeable, path: String, maxBytes: Int): Either[String, BigInt] =
    decodeBytes(encodeable, path).flatMap { b =>
      if b.length > maxBytes then Left(s"$path: ${b.length} bytes, more than the $maxBytes its type allows")
      else if b.nonEmpty && b(0) == 0 then Left(s"$path: leading zero byte (non-canonical integer)")
      else Right(BigInt(1, b))
    }

  private def decodeBytes(encodeable: RLPEncodeable, path: String): Either[String, Array[Byte]] =
    encodeable match
      case RLPValue(bytes) => Right(bytes)
      case _               => Left(s"$path: expected a byte string, found a list")

  private def listItems(encodeable: RLPEncodeable, path: String): Either[String, Seq[RLPEncodeable]] =
    encodeable match
      case list: RLPList => Right(list.items)
      case _             => Left(s"$path: expected a list, found a byte string")

  private def fields(encodeable: RLPEncodeable, path: String, arity: Int): Either[String, IndexedSeq[RLPEncodeable]] =
    listItems(encodeable, path).flatMap { items =>
      Either.cond(items.length == arity, items.toIndexedSeq, s"$path: ${items.length} fields, expected $arity")
    }

  /** Decodes every item, naming each by its position; stops at the first failure. */
  private def each[A, B](items: Seq[A], path: String)(
      decodeItem: (A, String) => Either[String, B]
  ): Either[String, Vector[B]] =
    items.iterator.zipWithIndex.foldLeft[Either[String, Vector[B]]](Right(Vector.empty)) { case (acc, (item, i)) =>
      acc.flatMap(decoded => decodeItem(item, s"$path[$i]").map(decoded :+ _))
    }

  private def strictlyAscending[A](keys: Seq[A], path: String, keyName: String)(
      less: (A, A) => Boolean
  ): Either[String, Unit] =
    keys.iterator
      .zip(keys.iterator.drop(1))
      .zipWithIndex
      .collectFirst {
        case ((previous, next), i) if !less(previous, next) =>
          s"$path[${i + 1}]: $keyName not strictly above $path[$i] (unsorted or duplicate)"
      }
      .toLeft(())
