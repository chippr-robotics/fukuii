package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.jsonrpc.NewPendingTransaction
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
import com.chipprbots.ethereum.transactions.TxAdmission.Rejection
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

/** The pool's rules against what it already holds (#1629 S0b; go-ethereum legacypool `add` / `validateTx` /
  * `validateAuth`, blobpool `validateTx`), for local submissions ([[SubmitLocalTransaction]]) and for peers'
  * transactions, on ETH (the shipped Sepolia config); on ETC (Mordor) the pool keeps its behaviour from before.
  */
class PendingTransactionsManagerPoolRulesSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with SecureRandomBuilder:

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.seconds, interval = 20.millis)

  private val shipped = ConfigFactory.load()
  private def chain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shipped.getConfig(s"fukuii.blockchains.$name"))
  private val sepolia: BlockchainConfig = chain("sepolia")
  private val mordor: BlockchainConfig = chain("mordor")

  private val Gwei = BigInt(1_000_000_000)
  private val Rich = BigInt(10).pow(24)

  private def ts(pick: com.chipprbots.ethereum.utils.ForkTimestamps => Option[Long]): Long =
    pick(sepolia.forkTimestamps).getOrElse(fail("fork timestamp not configured")) + 1
  private val pragueHead: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
    number = BlockNumber(9_000_000),
    unixTimestamp = Timestamp(ts(_.pragueTimestamp)),
    gasLimit = GasAmount(60_000_000),
    extraFields = HefPostOlympia(Gwei)
  )
  private val cancunHead: BlockHeader = pragueHead.copy(unixTimestamp = Timestamp(ts(_.cancunTimestamp)))
  private val mordorHead: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(10_000_000))

  def poolConfigWith(bump: Int): TxPoolConfig = new TxPoolConfig:
    override val txPoolSize: Int = 300
    override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
    override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
    override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
    override val priceBump: Int = bump

  /** A pool for `cfg`; with `state`, its best block is `head` over that state (the peer path reads it). */
  class Pool(
      val cfg: BlockchainConfig,
      val head: BlockHeader,
      bump: Int = TxPoolConfig.DefaultPriceBump,
      state: Map[Address, Account] = Map.empty
  ):
    given BlockchainConfig = cfg

    private val stateStorage = StateStorage.createTestStateStorage(EphemDataSource())._1
    private val stateRoot: Array[Byte] =
      state
        .foldLeft(
          MerklePatriciaTrie[Array[Byte], Account](stateStorage.getBackingStorage(0))(
            defaultByteArraySerializable,
            Account.accountSerializer
          )
        ) { case (trie, (address, account)) => trie.put(crypto.kec256(address.toArray), account) }
        .getRootHash
    private val best = Block(head.copy(stateRoot = TrieRoot(ByteString(stateRoot))), BlockBody.empty)
    private val reader: BlockchainReader = new BlockchainReader(null, null, null, null, null, null, null, null):
      override def getBestBlockHeader: Option[BlockHeader] = Some(best.header)
      override def getBestBlock: Option[Block] = Some(best)

    val ref: ActorRef[Command] = spawn(
      PendingTransactionsManager(
        poolConfigWith(bump),
        createTestProbe[PeerManagerActor.Command]().ref,
        createTestProbe[NetworkPeerManagerActor.Command]().ref,
        createTestProbe[PeerEventBusActor.Command]().ref,
        spawn(Topic[NewPendingTransaction](s"pending-${java.util.UUID.randomUUID()}")),
        blockchainReader = reader,
        stateStorage = if state.isEmpty then null else stateStorage,
        chainConfig = cfg
      )
    )

    def submit(stx: SignedTransaction, account: Account): Either[Rejection, Unit] =
      val probe = createTestProbe[Either[Rejection, Unit]]()
      ref ! SubmitLocalTransaction(stx, SignedTransaction.getSender(stx).get, account, head, None, probe.ref)
      probe.receiveMessage(5.seconds)

    def pooled: Seq[SignedTransaction] =
      val probe = createTestProbe[PendingTransactionsResponse]()
      ref ! GetPendingTransactionsReq(probe.ref)
      probe.receiveMessage(5.seconds).pendingTransactions.map(_.stx.tx)

  private def newKey(): AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
  private def funded(balance: BigInt = Rich, nonce: BigInt = 0, code: Boolean = false): Account =
    Account(
      nonce = UInt256(nonce),
      balance = UInt256(balance),
      codeHash = if code then CodeHash(ByteString(crypto.kec256(Array[Byte](1)))) else Account.EmptyCodeHash
    )

  private def dynamic(
      cfg: BlockchainConfig,
      nonce: BigInt = 0,
      maxFee: BigInt = 2 * Gwei,
      tip: BigInt = Gwei,
      value: BigInt = 1
  ): TransactionWithDynamicFee =
    TransactionWithDynamicFee(
      cfg.chainId.value,
      nonce,
      tip,
      maxFee,
      GasAmount(21_000),
      Some(Address(0x42)),
      value,
      ByteString.empty,
      Nil
    )

  private def legacy(nonce: BigInt = 0, price: BigInt = 2 * Gwei): LegacyTransaction =
    LegacyTransaction(nonce, GasPrice(price), GasAmount(21_000), Some(Address(0x42)), 1, ByteString.empty)

  private def signed(tx: Transaction, k: AsymmetricCipherKeyPair)(using cfg: BlockchainConfig): SignedTransaction =
    SignedTransaction.sign(tx, k, Some(cfg.chainId.value))

  // ---------------------------------------------------------------------------------------------------------------

  "a local submission on ETH" should "be refused as already known when the same tx is pooled" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val stx = signed(dynamic(sepolia), k)
    pool.submit(stx, funded()) shouldBe Right(())
    pool.submit(stx, funded()).left.map(_.message) shouldBe Left("already known")
    pool.pooled shouldBe Seq(stx)
  }

  it should "replace a pooled tx of the same nonce only with both caps raised by the price bump" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val first = signed(dynamic(sepolia, maxFee = 20 * Gwei, tip = 10 * Gwei), k)
    pool.submit(first, funded()) shouldBe Right(())
    // +5% on both caps; the fee cap at +10% but the tip unchanged
    pool
      .submit(signed(dynamic(sepolia, maxFee = 21 * Gwei, tip = 10_500_000_000L), k), funded())
      .left
      .map(_.message) shouldBe
      Left("replacement transaction underpriced")
    pool.submit(signed(dynamic(sepolia, maxFee = 22 * Gwei, tip = 10 * Gwei), k), funded()).left.map(_.message) shouldBe
      Left("replacement transaction underpriced")
    // exactly +10% on both: replaces
    val replacement = signed(dynamic(sepolia, maxFee = 22 * Gwei, tip = 11 * Gwei), k)
    pool.submit(replacement, funded()) shouldBe Right(())
    pool.pooled shouldBe Seq(replacement)
  }

  it should "apply the configured price bump" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead, bump = 20)
    import pool.given
    val k = newKey()
    pool.submit(signed(dynamic(sepolia, maxFee = 20 * Gwei, tip = 10 * Gwei), k), funded()) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, maxFee = 22 * Gwei, tip = 11 * Gwei), k), funded()).left.map(_.message) shouldBe
      Left("replacement transaction underpriced")
    pool.submit(signed(dynamic(sepolia, maxFee = 24 * Gwei, tip = 12 * Gwei), k), funded()) shouldBe Right(())
  }

  it should "refuse a tx the balance cannot cover on top of the sender's pending txs" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val cost = TxAdmission.upfrontCost(dynamic(sepolia))
    val account = funded(balance = cost * 3 / 2)
    pool.submit(signed(dynamic(sepolia, nonce = 0), k), account) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), k), account).left.map(_.message) shouldBe Left(
      s"insufficient funds for gas * price + value: balance ${cost * 3 / 2}, queued cost $cost, tx cost $cost, " +
        s"overshot ${cost * 2 - cost * 3 / 2}"
    )
  }

  it should "count only the executable run toward the overdraft, not a gapped tx" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val cost = TxAdmission.upfrontCost(dynamic(sepolia))
    val account = funded(balance = cost)
    pool.submit(signed(dynamic(sepolia, nonce = 5), k), account) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 0), k), account) shouldBe Right(())
  }

  it should "refuse a replacement whose extra cost overdraws the pending txs" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val cost = TxAdmission.upfrontCost(dynamic(sepolia))
    val bumped = dynamic(sepolia, maxFee = 4 * Gwei, tip = 2 * Gwei)
    val bump = TxAdmission.upfrontCost(bumped) - cost
    val account = funded(balance = 2 * cost + bump - 1)
    pool.submit(signed(dynamic(sepolia, nonce = 0), k), account) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), k), account) shouldBe Right(())
    pool.submit(signed(bumped, k), account).left.map(_.message) shouldBe Left(
      s"insufficient funds for gas * price + value: balance ${2 * cost + bump - 1}, queued cost ${2 * cost}, " +
        s"tx bumped $bump, overshot 1"
    )
  }

  it should "hold a delegated sender to one in-flight tx, from Prague" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    val delegated = funded(code = true)
    pool.submit(signed(dynamic(sepolia, nonce = 1), k), delegated).left.map(_.message) shouldBe
      Left("gapped-nonce tx from delegated accounts")
    pool.submit(signed(dynamic(sepolia, nonce = 0), k), delegated) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), k), delegated).left.map(_.message) shouldBe
      Left("in-flight transaction limit reached for delegated accounts")
    // replacing the one in flight is allowed
    pool.submit(signed(dynamic(sepolia, nonce = 0, maxFee = 4 * Gwei, tip = 2 * Gwei), k), delegated) shouldBe Right(())
  }

  it should "not apply the delegation limit before set-code transactions exist (Cancun head)" taggedAs UnitTest in {
    val pool = new Pool(sepolia, cancunHead)
    import pool.given
    val k = newKey()
    pool.submit(signed(dynamic(sepolia, nonce = 0), k), funded(code = true)) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), k), funded(code = true)) shouldBe Right(())
  }

  /** An EIP-7702 authorization of `delegate` signed by `authority`. */
  private def authorization(authority: AsymmetricCipherKeyPair)(using cfg: BlockchainConfig): SetCodeAuthorization =
    val chainId = cfg.chainId.value
    val delegate = Address(0x77)
    val hash = crypto.kec256(
      rlp.encode(
        PrefixedRLPEncodable(
          0x05,
          RLPList(toEncodeable(chainId), toEncodeable(delegate.toArray), toEncodeable(BigInt(0)))
        )
      )
    )
    val sig = ECDSASignature.sign(hash, authority)
    SetCodeAuthorization(chainId, delegate, 0, sig.v - 27, sig.r, sig.s)

  private def setCode(nonce: BigInt, auths: List[SetCodeAuthorization])(using cfg: BlockchainConfig) =
    SetCodeTransaction(
      cfg.chainId.value,
      nonce,
      Gwei,
      2 * Gwei,
      GasAmount(100_000),
      Some(Address(0x42)),
      0,
      ByteString.empty,
      Nil,
      auths
    )

  it should "recover an authorization's authority as go-ethereum does" taggedAs UnitTest in {
    given BlockchainConfig = sepolia
    val authority = newKey()
    TxAdmission.setCodeAuthorities(setCode(0, List(authorization(authority), authorization(authority)))) shouldBe
      Seq(Address(authority))
  }

  it should "hold a sender a pooled set-code tx authorizes to one in-flight tx" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val (sender, authority) = (newKey(), newKey())
    pool.submit(signed(setCode(0, List(authorization(authority))), sender), funded()) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 0), authority), funded()) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), authority), funded()).left.map(_.message) shouldBe
      Left("in-flight transaction limit reached for delegated accounts")
  }

  it should "refuse a set-code tx whose authority has more than one pooled tx" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val (sender, authority) = (newKey(), newKey())
    pool.submit(signed(dynamic(sepolia, nonce = 0), authority), funded()) shouldBe Right(())
    pool.submit(signed(dynamic(sepolia, nonce = 1), authority), funded()) shouldBe Right(())
    pool.submit(signed(setCode(0, List(authorization(authority))), sender), funded()).left.map(_.message) shouldBe
      Left("authority already reserved")
  }

  it should "report blobpool's replacement detail for a blob tx, at blobpool's 100% bump" taggedAs UnitTest in {
    val pool = new Pool(sepolia, pragueHead)
    import pool.given
    val k = newKey()
    def blob(maxFee: BigInt, tip: BigInt, blobFee: BigInt) = BlobTransaction(
      sepolia.chainId.value,
      0,
      tip,
      maxFee,
      GasAmount(21_000),
      Some(Address(0x42)),
      0,
      ByteString.empty,
      Nil,
      blobFee,
      List(BlobVersionedHash(ByteString(Array.fill[Byte](32)(1))))
    )
    pool.submit(signed(blob(20 * Gwei, 10 * Gwei, 10 * Gwei), k), funded()) shouldBe Right(())
    def refusal(tx: BlobTransaction) = pool.submit(signed(tx, k), funded()).left.map(_.message)
    refusal(blob(20 * Gwei, 11 * Gwei, 11 * Gwei)) shouldBe
      Left(s"replacement transaction underpriced: new tx gas fee cap ${20 * Gwei} <= ${20 * Gwei} queued")
    refusal(blob(22 * Gwei, 11 * Gwei, 10 * Gwei)) shouldBe
      Left(s"replacement transaction underpriced: new tx blob gas fee cap ${10 * Gwei} <= ${10 * Gwei} queued")
    // +10% is enough in the legacy pool, not here
    refusal(blob(22 * Gwei, 11 * Gwei, 11 * Gwei)) shouldBe Left(
      s"replacement transaction underpriced: new tx gas fee cap ${22 * Gwei} < ${20 * Gwei} queued + 100% replacement penalty"
    )
    refusal(blob(40 * Gwei, 19 * Gwei, 20 * Gwei)) shouldBe Left(
      s"replacement transaction underpriced: new tx gas tip cap ${19 * Gwei} < ${10 * Gwei} queued + 100% replacement penalty"
    )
    pool.submit(signed(blob(40 * Gwei, 20 * Gwei, 20 * Gwei), k), funded()) shouldBe Right(())
  }

  "a local submission on Mordor" should "keep the pool's behaviour from before: no pool-state rules" taggedAs UnitTest in {
    val pool = new Pool(mordor, mordorHead)
    import pool.given
    val k = newKey()
    val stx = signed(legacy(price = 20 * Gwei), k)
    pool.submit(stx, funded()) shouldBe Right(())
    pool.submit(stx, funded()) shouldBe Right(())
    // a cheaper tx of the same nonce still overrides, as AddOrOverrideTransaction did
    val cheaper = signed(legacy(price = 10 * Gwei), k)
    pool.submit(cheaper, funded()) shouldBe Right(())
    pool.pooled shouldBe Seq(cheaper)
    // and no overdraft across pending txs
    val cost = TxAdmission.upfrontCost(legacy())
    pool.submit(signed(legacy(nonce = 1), k), funded(balance = cost)) shouldBe Right(())
  }

  // ---------------------------------------------------------------------------------------------------------------

  "a peer's txs on ETH" should "meet the overdraft rule against the sender's pooled txs" taggedAs UnitTest in {
    given BlockchainConfig = sepolia
    val k = newKey()
    val sender = Address(k)
    val cost = TxAdmission.upfrontCost(dynamic(sepolia))
    val pool = new Pool(sepolia, pragueHead, state = Map(sender -> funded(balance = cost * 3 / 2)))
    val txs = (0 to 1).map(n => SignedTransactionWithSender(signed(dynamic(sepolia, nonce = n), k), sender))
    pool.ref ! ProperSignedTransactions(txs.toSet, PeerId("peer"))
    eventually(pool.pooled shouldBe Seq(txs.head.tx))
  }

  "a peer's txs on Mordor" should "not meet it: the pool keeps its behaviour from before" taggedAs UnitTest in {
    given BlockchainConfig = mordor
    val k = newKey()
    val sender = Address(k)
    val cost = TxAdmission.upfrontCost(legacy())
    val pool = new Pool(mordor, mordorHead, state = Map(sender -> funded(balance = cost * 3 / 2)))
    val txs = (0 to 1).map(n => SignedTransactionWithSender(signed(legacy(nonce = n), k), sender))
    pool.ref ! ProperSignedTransactions(txs.toSet, PeerId("peer"))
    eventually(pool.pooled.toSet shouldBe txs.map(_.tx).toSet)
  }

  "txPool.price-bump and blob-price-bump" should "default to go-ethereum's 10 and 100, below 1 read as default" taggedAs UnitTest in {
    TxPoolConfig(shipped.getConfig("fukuii")).priceBump shouldBe 10
    TxPoolConfig(
      ConfigFactory.parseString("txPool.price-bump = 25").withFallback(shipped.getConfig("fukuii"))
    ).priceBump shouldBe 25
    TxPoolConfig(
      ConfigFactory.parseString("txPool.price-bump = 0").withFallback(shipped.getConfig("fukuii"))
    ).priceBump shouldBe 10
    TxPoolConfig(shipped.getConfig("fukuii")).blobPriceBump shouldBe 100
    TxPoolConfig(
      ConfigFactory.parseString("txPool.blob-price-bump = 0").withFallback(shipped.getConfig("fukuii"))
    ).blobPriceBump shouldBe 100
  }
