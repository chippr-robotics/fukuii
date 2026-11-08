package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.UInt256RLPImplicits.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.InvalidJump
import com.chipprbots.ethereum.vm.MockWorldState
import com.chipprbots.ethereum.vm.PinHarness

/** Spec 021 S0: the codec goldens (forge S2, data-model §9) and rows P07 to P09.
  *
  * Every byte that leaves `UInt256` through a codec, captured as literals on the pre-swap (`BigInt`-backed) tree:
  *   - Account RLP at nonce = balance in {0x7f, 0x80, 0xff, 0x100, 2^255, 2^256 - 1};
  *   - the CREATE address at preimage nonce in {1, 0x7f, 0x80, 0x100, 2^64 - 2};
  *   - access-list storage-key bytes, and the hash of a type-1 transaction carrying them;
  *   - storage-trie key hashes for slots {0, 1, 2^255, 2^256 - 1}, and a root holding all four;
  *   - JSON-RPC quantities of {0, 0xf, 2^255, 2^256 - 1};
  *   - `toHexString`, `toString` and `toDecString`, and the `InvalidJump` string.
  *
  * The structLog stack hex and the prestate nonce (decimal) are pinned by TraceGoldenSpec's goldens.
  *
  * Where an independent source exists the literal is cross-checked against it: the RLP rules (re-implemented here), the
  * well-known keccak of the 32-byte slots 0 and 1, core-geth's CREATE vector, and the JSON-RPC quantity rule. S1 swaps
  * the representation: these bytes must not move.
  */
// scalastyle:off magic.number
class UInt256GoldenBytesSpec extends AnyFunSuite with Matchers:

  private def pow2(n: Int): BigInt = BigInt(2).pow(n)
  private def u(n: BigInt): UInt256 = UInt256(n)
  private def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)
  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray)

  // ── An independent RLP encoder (Yellow Paper appendix B), for the cross-checks ─────────────────────────────────

  private def minimal(n: BigInt): Array[Byte] = if n == 0 then Array.empty else n.toByteArray.dropWhile(_ == 0)
  private def lengthPrefix(offset: Int, length: Int): Array[Byte] =
    if length <= 55 then Array((offset + length).toByte)
    else
      val len = minimal(BigInt(length))
      Array((offset + 55 + len.length).toByte) ++ len
  private def rlpString(b: Array[Byte]): Array[Byte] =
    if b.length == 1 && (b(0) & 0xff) < 0x80 then b else lengthPrefix(0x80, b.length) ++ b
  private def rlpList(items: Array[Byte]*): Array[Byte] =
    val payload = items.foldLeft(Array.empty[Byte])(_ ++ _)
    lengthPrefix(0xc0, payload.length) ++ payload

  // ── The rows ─────────────────────────────────────────────────────────────────────────────────────────────────────

  private val AccountValues: Seq[BigInt] =
    Seq(BigInt(0x7f), BigInt(0x80), BigInt(0xff), BigInt(0x100), pow2(255), pow2(256) - 1)

  /** The account leaf's RLP, as the state trie stores it. */
  private def accountRlp(v: BigInt): Array[Byte] =
    Account.accountSerializer.toBytes(Account(nonce = u(v), balance = u(v)))

  private val CreateNonces: Seq[BigInt] = Seq(BigInt(1), BigInt(0x7f), BigInt(0x80), BigInt(0x100), pow2(64) - 2)

  /** core-geth's sender in UInt256RlpEncodingSpec. */
  private val Creator: Address = Address("0xf7f04e1052c6a30f651b07bb8f6bedf4844137f5")

  private val Slots: Seq[BigInt] = Seq(BigInt(0), BigInt(1), pow2(255), pow2(256) - 1)

  private val Words: Seq[BigInt] =
    Seq(BigInt(0), BigInt(0xf), BigInt(0x7f), BigInt(0x80), BigInt(0x100), pow2(255), pow2(256) - 1)

  /** The CREATE address of `Creator` at preimage nonce `n`: `createAddress` runs after the nonce increment, so the
    * account holds n + 1.
    */
  private def createAddress(n: BigInt): Address =
    MockWorldState()
      .saveAccount(Creator, Account(nonce = u(n + 1)))
      .createAddress(Creator)

  private val accessListItem: AccessListItem =
    AccessListItem(Address(0xc0de), Slots.map(StorageKey(_)).toList)

  private val accessListTx: SignedTransaction = SignedTransaction(
    TransactionWithAccessList(
      chainId = 1,
      nonce = 0,
      gasPrice = GasPrice(1),
      gasLimit = GasAmount(30000),
      receivingAddress = Some(Address(0xc0de)),
      value = 0,
      payload = ByteString.empty,
      accessList = List(accessListItem)
    ),
    ECDSASignature(r = 1, s = 2, v = 0)
  )

  /** keccak(pad32(slot)): the storage-trie key, through the serializer the world state uses. */
  private def slotKeyHash(slot: BigInt): String =
    hex(kec256(EthereumUInt256Mpt.byteArrayBigIntSerializer.toBytes(u(slot).toBigInt)))

  private def storageRoot(entries: Seq[(BigInt, BigInt)]): String =
    val nodes = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    val empty = EthereumUInt256Mpt.storageMpt(Account.EmptyStorageRootHash.value, nodes)
    hex(entries.foldLeft(empty) { case (t, (k, v)) => t.put(u(k).toBigInt, u(v).toBigInt) }.getRootHash)

  private val json = new JsonMethodsImplicits {}

  private def table: String =
    val rows = Seq.newBuilder[String]
    for v <- AccountValues do rows += s"account.rlp nonce=balance=0x${v.toString(16)} = ${hex(accountRlp(v))}"
    for n <- CreateNonces do rows += s"create.address nonce=0x${n.toString(16)} = ${hex(createAddress(n).bytes)}"
    rows += s"accesslist.item.rlp = ${hex(rlp.encode(ETHPackets.accessListItemCodec.encode(accessListItem)))}"
    rows += s"accesslist.tx.hash = ${hex(accessListTx.hash.value)}"
    for s <- Slots do rows += s"storage.keyhash slot=0x${s.toString(16)} = ${slotKeyHash(s)}"
    rows += s"storage.root slots=0,1,2^255,2^256-1 = ${storageRoot(Slots.zip(Seq(BigInt(1), pow2(255), pow2(256) - 1, BigInt(0x80))))}"
    for w <- Seq(BigInt(0), BigInt(0xf), pow2(255), pow2(256) - 1) do
      rows += s"json.quantity 0x${w.toString(16)} = ${json.encodeAsHex(u(w)).s}"
    for w <- Words do
      val x = u(w)
      rows += s"rlp 0x${w.toString(16)} = ${hex(rlp.encode(x.toRLPEncodable))}"
      rows += s"toHexString 0x${w.toString(16)} = ${x.toHexString}"
      rows += s"toString 0x${w.toString(16)} = ${x.toString}"
      rows += s"toDecString 0x${w.toString(16)} = ${x.toDecString}"
      rows += s"bytes 0x${w.toString(16)} = ${hex(x.bytes)}"
      rows += s"invalidJump 0x${w.toString(16)} = ${InvalidJump(x).toString}"
    rows.result().mkString("", "\n", "\n")

  // Captured on the pre-swap tree (spec 021 S0). Never edited by a slice.
  private val Expected: String =
    """|account.rlp nonce=balance=0x7f = f8447f7fa056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |account.rlp nonce=balance=0x80 = f84681808180a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |account.rlp nonce=balance=0xff = f84681ff81ffa056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |account.rlp nonce=balance=0x100 = f848820100820100a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |account.rlp nonce=balance=0x8000000000000000000000000000000000000000000000000000000000000000 = f884a08000000000000000000000000000000000000000000000000000000000000000a08000000000000000000000000000000000000000000000000000000000000000a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |account.rlp nonce=balance=0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = f884a0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
       |create.address nonce=0x1 = 62187b4aa3bda5bf7580fa4aa89659f018bb958f
       |create.address nonce=0x7f = ecde4e53115aee43f06a055633e018d0bdc1268d
       |create.address nonce=0x80 = bfb5560c46eab49ba5d2d466a677442e2ccd7e9a
       |create.address nonce=0x100 = 55acb0d0b96d9fab55a51bc5b71ea381ef2fde02
       |create.address nonce=0xfffffffffffffffe = 0dea1ba7661602e6b76c165f001ee9fb987c8e28
       |accesslist.item.rlp = f89b94000000000000000000000000000000000000c0def884a00000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000001a08000000000000000000000000000000000000000000000000000000000000000a0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
       |accesslist.tx.hash = d8479cacf53499c876cdb9f00fb417166b94f51374069e5e30e9d48d90b78d6a
       |storage.keyhash slot=0x0 = 290decd9548b62a8d60345a988386fc84ba6bc95484008f6362f93160ef3e563
       |storage.keyhash slot=0x1 = b10e2d527612073b26eecdfd717e6a320cf44b4afac2b0732d9fcbe2b7fa0cf6
       |storage.keyhash slot=0x8000000000000000000000000000000000000000000000000000000000000000 = de3995408d4211c18871603faad2abbdd832ff9dfb97528065798ee20dc635bf
       |storage.keyhash slot=0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = a9c584056064687e149968cbab758a3376d22aedc6a55823d1b3ecbee81b8fb9
       |storage.root slots=0,1,2^255,2^256-1 = e3ba8595234516a3f59dee438661f67195bf1da7bd78971bdb0f8d46ab579b83
       |json.quantity 0x0 = 0x0
       |json.quantity 0xf = 0xf
       |json.quantity 0x8000000000000000000000000000000000000000000000000000000000000000 = 0x8000000000000000000000000000000000000000000000000000000000000000
       |json.quantity 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
       |rlp 0x0 = 80
       |toHexString 0x0 = 0x00
       |toString 0x0 = 0
       |toDecString 0x0 = 0
       |bytes 0x0 = 0000000000000000000000000000000000000000000000000000000000000000
       |invalidJump 0x0 = InvalidJump(0x00)
       |rlp 0xf = 0f
       |toHexString 0xf = 0x0f
       |toString 0xf = 15
       |toDecString 0xf = 15
       |bytes 0xf = 000000000000000000000000000000000000000000000000000000000000000f
       |invalidJump 0xf = InvalidJump(0x0f)
       |rlp 0x7f = 7f
       |toHexString 0x7f = 0x7f
       |toString 0x7f = 127
       |toDecString 0x7f = 127
       |bytes 0x7f = 000000000000000000000000000000000000000000000000000000000000007f
       |invalidJump 0x7f = InvalidJump(0x7f)
       |rlp 0x80 = 8180
       |toHexString 0x80 = 0x80
       |toString 0x80 = 128
       |toDecString 0x80 = 128
       |bytes 0x80 = 0000000000000000000000000000000000000000000000000000000000000080
       |invalidJump 0x80 = InvalidJump(0x80)
       |rlp 0x100 = 820100
       |toHexString 0x100 = 0x0100
       |toString 0x100 = 256
       |toDecString 0x100 = 256
       |bytes 0x100 = 0000000000000000000000000000000000000000000000000000000000000100
       |invalidJump 0x100 = InvalidJump(0x0100)
       |rlp 0x8000000000000000000000000000000000000000000000000000000000000000 = a08000000000000000000000000000000000000000000000000000000000000000
       |toHexString 0x8000000000000000000000000000000000000000000000000000000000000000 = 0x8000000000000000000000000000000000000000000000000000000000000000
       |toString 0x8000000000000000000000000000000000000000000000000000000000000000 = -57896044618658097711785492504343953926634992332820282019728792003956564819968
       |toDecString 0x8000000000000000000000000000000000000000000000000000000000000000 = 57896044618658097711785492504343953926634992332820282019728792003956564819968
       |bytes 0x8000000000000000000000000000000000000000000000000000000000000000 = 8000000000000000000000000000000000000000000000000000000000000000
       |invalidJump 0x8000000000000000000000000000000000000000000000000000000000000000 = InvalidJump(0x8000000000000000000000000000000000000000000000000000000000000000)
       |rlp 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = a0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
       |toHexString 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
       |toString 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = -1
       |toDecString 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = 115792089237316195423570985008687907853269984665640564039457584007913129639935
       |bytes 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
       |invalidJump 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff = InvalidJump(0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff)
       |""".stripMargin

  test("codec goldens: every byte and string that leaves UInt256 is today's", UnitTest) {
    PinHarness.assertSameText("spec021/codec-goldens.txt", Expected, table)
  }

  // ── Independent cross-checks ─────────────────────────────────────────────────────────────────────────────────────

  test("P07 RLP of a word follows the RLP rules", UnitTest) {
    def enc(n: BigInt): String = hex(rlp.encode(u(n).toRLPEncodable))
    enc(0) shouldBe "80"
    enc(0x7f) shouldBe "7f"
    enc(0x80) shouldBe "8180"
    enc(0x100) shouldBe "820100"
    enc(pow2(256) - 1) shouldBe "a0" + "ff" * 32
    for w <- Words do withClue(s"0x${w.toString(16)}: ")(enc(w) shouldBe hex(rlpString(minimal(w))))
  }

  test("Account RLP equals the RLP rules applied to (nonce, balance, storage root, code hash)", UnitTest) {
    for v <- AccountValues do
      withClue(s"0x${v.toString(16)}: ") {
        val independent = rlpList(
          rlpString(minimal(v)),
          rlpString(minimal(v)),
          rlpString(Account.EmptyStorageRootHash.value.toArray),
          rlpString(Account.EmptyCodeHash.value.toArray)
        )
        hex(accountRlp(v)) shouldBe hex(independent)
      }
  }

  test("the CREATE address is keccak(rlp([sender, nonce]))[12:], core-geth's nonce-0 vector included", UnitTest) {
    for n <- CreateNonces do
      withClue(s"nonce 0x${n.toString(16)}: ") {
        val preimage = rlpList(rlpString(Creator.bytes.toArray), rlpString(minimal(n)))
        createAddress(n) shouldBe Address(ByteString(kec256(preimage)))
      }
    createAddress(0) shouldBe Address("0x2fae8af94cdc68452042ccfc2abf25a9120fdb2e")
  }

  test("storage keys are keccak of the 32-byte slot: the well-known hashes of slots 0 and 1", UnitTest) {
    slotKeyHash(0) shouldBe "290decd9548b62a8d60345a988386fc84ba6bc95484008f6362f93160ef3e563"
    slotKeyHash(1) shouldBe "b10e2d527612073b26eecdfd717e6a320cf44b4afac2b0732d9fcbe2b7fa0cf6"
    for s <- Slots do withClue(s"slot 0x${s.toString(16)}: ")(slotKeyHash(s) shouldBe hex(kec256(u(s).bytes.toArray)))
    // Access-list keys go on the wire as the same 32 bytes.
    val encoded = rlp.encode(ETHPackets.accessListItemCodec.encode(accessListItem))
    hex(encoded) shouldBe hex(
      rlpList(rlpString(Address(0xc0de).bytes.toArray), rlpList(Slots.map(s => rlpString(u(s).bytes.toArray))*))
    )
  }

  test("P08, P09 and the JSON-RPC quantity rule: hex without leading zeros, signed decimal toString", UnitTest) {
    u(0).toHexString shouldBe "0x00"
    u(0xf).toHexString shouldBe "0x0f"
    UInt256.MaxValue.toHexString shouldBe "0x" + "f" * 64
    u(pow2(255)).toString shouldBe (-pow2(255)).toString
    UInt256.MaxValue.toString shouldBe "-1"
    UInt256.MaxValue.toDecString shouldBe (pow2(256) - 1).toString
    json.encodeAsHex(u(0)).s shouldBe "0x0"
    json.encodeAsHex(u(0xf)).s shouldBe "0xf"
    json.encodeAsHex(u(pow2(255))).s shouldBe "0x8" + "0" * 63
    json.encodeAsHex(UInt256.MaxValue).s shouldBe "0x" + "f" * 64
  }
