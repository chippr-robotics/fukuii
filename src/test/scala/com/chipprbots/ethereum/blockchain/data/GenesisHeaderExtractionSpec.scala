package com.chipprbots.ethereum.blockchain.data

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import scala.io.Source

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ephemery.ChainConfRenderer
import com.chipprbots.ethereum.ephemery.GenesisJsonEncoder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig

/** The pure genesis function the Ephemery release engine hashes with (spec 028 R3, slice S2), against the loader a node
  * stores its genesis with.
  *
  * `GenesisDataLoader.genesisHeader` is the state root over a throwaway in-memory trie, then the header logic the
  * loader has always had. It must give, for every genesis a node loads, the header the loader stores: ETC, Mordor,
  * Sepolia, Platåberget, Gorgoroth, both Ephemery iterations, and an allocation with code and storage (two accounts
  * holding the same storage, whose tries share nodes). The loader's own `prepareHeader` stays the overridable hook,
  * takes the state root it is given and does not recompute it.
  */
class GenesisHeaderExtractionSpec extends AnyFlatSpec with Matchers:

  private class Env extends EphemBlockchainTestSetup

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  private def ephemery(iteration: Int): BlockchainConfig =
    BlockchainsConfig(
      ConfigFactory
        .empty()
        .withValue("network", ConfigValueFactory.fromAnyRef("ephemery"))
        .withValue(
          "custom-chains-dir",
          ConfigValueFactory.fromAnyRef(
            new File(getClass.getResource(s"/ephemery/ephemery-$iteration").toURI).getAbsolutePath
          )
        )
    ).blockchainConfig

  /** The genesis a node loads under `config`, as `GenesisDataLoader.loadGenesisData()` finds it. */
  private def genesisOf(config: BlockchainConfig): GenesisData =
    val text = config.customGenesisJsonOpt
      .orElse(config.customGenesisFileOpt.map(f => new String(Files.readAllBytes(new File(f).toPath), "UTF-8")))
      .getOrElse {
        val src = Source.fromResource("blockchain/default-genesis.json")
        try src.getLines().mkString
        finally src.close()
      }
    GenesisDataLoader.parseGenesisData(text).get

  private def loader(env: Env): GenesisDataLoader =
    new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    )

  /** The header the loader stores at block 0 for `data` under `config`. */
  private def stored(data: GenesisData)(using config: BlockchainConfig): BlockHeader =
    val env = new Env
    loader(env).loadGenesisData(data).get
    env.blockchainReader.getBlockHeaderByNumber(0).getOrElse(fail("no genesis header was written"))

  private def hex(h: BlockHeader): String = Hex.toHexString(h.hash.value.toArray)

  // ---------------------------------------------------------------------------------------------------------------
  private val Builtins: Seq[(String, () => BlockchainConfig)] = Seq(
    "etc" -> (() => shipped("etc")),
    "mordor" -> (() => shipped("mordor")),
    "sepolia" -> (() => shipped("sepolia")),
    "plataberget" -> (() => shipped("plataberget")),
    "gorgoroth" -> (() => shipped("gorgoroth")),
    "ephemery-164" -> (() => ephemery(164)),
    "ephemery-165" -> (() => ephemery(165))
  )

  Builtins.foreach { case (name, config) =>
    "genesisHeader" should s"equal the header the loader stores, for $name" taggedAs (UnitTest) in {
      given BlockchainConfig = config()
      val data = genesisOf(summon[BlockchainConfig])
      val header = stored(data)
      val pure = GenesisDataLoader.genesisHeader(data)
      pure.hash shouldBe header.hash
      pure shouldBe header
    }
  }

  it should "equal the published hashes of the Ephemery iterations (Prague shape 164, Amsterdam shape 165)" taggedAs (
    UnitTest
  ) in {
    hex(GenesisDataLoader.genesisHeader(genesisOf(ephemery(164)))(using ephemery(164))) shouldBe
      "39c91f8ef961470061e5f6eb1e2d8a05c79f630a41644cefb17397960c4868f0"
    hex(GenesisDataLoader.genesisHeader(genesisOf(ephemery(165)))(using ephemery(165))) shouldBe
      "9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc"
  }

  it should "equal Hoodi's published genesis hash: the Cancun header shape (beacon 5)" taggedAs (UnitTest) in {
    // Hoodi (eth-clients/hoodi, geth params/config.go): shanghaiTime and cancunTime 0, pragueTime 1742999832 after the
    // genesis timestamp 1742212800, so block 0 is a Cancun header. The raw upstream genesis.json (decimal timestamp,
    // "0x01" difficulty) goes through the release encoder, the same step a release takes.
    import com.chipprbots.ethereum.ephemery.StrictJson
    val rawText = Source.fromResource("ephemery/pins/hoodi-genesis.json").mkString
    val encoded = StrictJson.parse(rawText) match
      case Right(g: StrictJson.JObj) => GenesisJsonEncoder.encode(g).fold(r => fail(r.message), identity)
      case other                     => fail(s"not an object: $other")
    val params = ChainConfRenderer.Params(
      release = "hoodi",
      chainId = BigInt(560048),
      genesisTimestamp = 1742212800L,
      resetInterval = 0L,
      shanghai = 0L,
      cancun = 0L,
      prague = 1742999832L,
      osaka = Some(1761677592L),
      bpo1 = Some(1762365720L),
      bpo2 = Some(1762955544L),
      amsterdam = None,
      blobSchedule = Seq(
        ("cancun", BigInt(3), BigInt(6), BigInt(3338477)),
        ("prague", BigInt(6), BigInt(9), BigInt(5007716)),
        ("bpo1", BigInt(10), BigInt(15), BigInt(8346193)),
        ("bpo2", BigInt(14), BigInt(21), BigInt(11684671))
      ),
      depositContract = "0x00000000219ab540356cBB839Cbe05303d7705Fa",
      bootnodes = Nil,
      publishedHash = "0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"
    )
    val conf = ChainConfRenderer
      .render(params)
      .replace("custom-genesis-file = { include required(\"ephemery-genesis.json\") }", "")
    val config = BlockchainConfig.fromRawConfig(ConfigFactory.parseString(conf))
    val data = GenesisDataLoader.parseGenesisData(encoded.json).get
    val header = GenesisDataLoader.genesisHeader(data)(using config)
    header.extraFields shouldBe a[BlockHeader.HeaderExtraFields.HefPostCancun]
    hex(header) shouldBe "bbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"
    given BlockchainConfig = config
    stored(data).hash shouldBe header.hash
  }

  // ---------------------------------------------------------------------------------------------------------------
  private val Sepolia = () => shipped("sepolia")
  private val Mordor = () => shipped("mordor")
  private val Amsterdam = () => ephemery(165)

  private def account(
      balance: Int,
      code: Option[Array[Byte]] = None,
      nonce: Option[Int] = None,
      storage: Option[Map[UInt256, UInt256]] = None
  ): GenesisAccount =
    GenesisAccount(None, UInt256(balance), code.map(ByteString(_)), nonce.map(UInt256(_)), storage)

  private def address(n: Int): String = "0x" + f"$n%040x"

  private def withStorage(alloc: Map[String, GenesisAccount]): GenesisData =
    GenesisData(
      nonce = ByteString(Array[Byte](0, 0, 0, 0, 0, 0, 0x12, 0x34)),
      mixHash = None,
      difficulty = "0x1",
      extraData = ByteString.empty,
      gasLimit = "0x2255100",
      coinbase = ByteString(new Array[Byte](20)),
      timestamp = "0x0",
      alloc = alloc,
      baseFeePerGas = Some("0x3b9aca00")
    )

  private val Shared = Map(UInt256(0) -> UInt256(0x0bad), UInt256(0x60a7) -> UInt256(0xdead))
  private val Code = Array[Byte](0x60, 0x01, 0x60, 0x00, 0x55, 0x00)

  private val allocs: Seq[(String, Map[String, GenesisAccount])] = Seq(
    "an account with code and storage" -> Map(
      address(1) -> account(1, Some(Code), Some(1), Some(Shared)),
      address(2) -> account(7)
    ),
    "two accounts with identical storage roots (their storage tries share every node)" -> Map(
      address(1) -> account(1, Some(Code), Some(1), Some(Shared)),
      address(2) -> account(2, Some(Array[Byte](0x00)), None, Some(Shared)),
      address(3) -> account(3, None, None, Some(Map(UInt256(0) -> UInt256(0x0bad)))),
      address(4) -> account(4, None, None, Some(Map(UInt256(0) -> UInt256(0x0bad), UInt256(0x60a7) -> UInt256(0xdead))))
    ),
    "a zero storage slot, a short address and a default nonce" -> Map(
      "0x1" -> account(5, None, None, Some(Map(UInt256(1) -> UInt256.Zero, UInt256(2) -> UInt256(2)))),
      address(9) -> account(0, None, Some(3))
    )
  )

  for
    (chain, config) <- Seq("sepolia" -> Sepolia, "mordor" -> Mordor, "ephemery-165" -> Amsterdam)
    (what, alloc) <- allocs
  do
    it should s"equal the stored header, on $chain, for $what" taggedAs (UnitTest) in {
      given BlockchainConfig = config()
      val data = withStorage(alloc)
      GenesisDataLoader.genesisHeader(data) shouldBe stored(data)
    }

  it should "not depend on the order of the allocation" taggedAs (UnitTest) in {
    given BlockchainConfig = Sepolia()
    val alloc = allocs(1)._2
    val reversed = scala.collection.immutable.ListMap(alloc.toSeq.reverse*)
    GenesisDataLoader.genesisHeader(withStorage(alloc)) shouldBe GenesisDataLoader.genesisHeader(withStorage(reversed))
  }

  // ---------------------------------------------------------------------------------------------------------------
  "the instance prepareHeader" should "take the state root it is given, and not compute one (forge 1)" taggedAs (
    UnitTest
  ) in {
    given BlockchainConfig = Sepolia()
    val data = withStorage(allocs.head._2)
    val injected = Array.fill[Byte](32)(0x42)
    var seen: Option[ByteString] = None
    val env = new Env
    val probing = new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    ):
      override protected def prepareHeader(d: GenesisData, root: Array[Byte])(implicit
          blockchainConfig: BlockchainConfig
      ): BlockHeader =
        seen = Some(ByteString(root))
        super.prepareHeader(d, injected)
    probing.loadGenesisData(data).get
    // the loader computed a root, passed it to the hook; the hook handed super another one, and that is what is stored
    seen.map(_.length) shouldBe Some(32)
    seen.get should not be ByteString(injected)
    seen.get shouldBe ByteString(GenesisDataLoader.pureStateRoot(data))
    env.blockchainReader.getBlockHeaderByNumber(0).get.stateRoot.value shouldBe ByteString(injected)
  }

  it should "stay overridable: an override still takes effect in loadGenesisData (as EestBlockchainReplay does)" taggedAs (
    UnitTest
  ) in {
    given BlockchainConfig = Sepolia()
    val data = withStorage(allocs.head._2)
    val substitute = GenesisDataLoader.genesisHeader(data).copy(gasLimit = GasAmount(12345))
    val env = new Env
    val overriding = new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    ):
      override protected def prepareHeader(d: GenesisData, root: Array[Byte])(implicit
          blockchainConfig: BlockchainConfig
      ): BlockHeader = substitute
    overriding.loadGenesisData(data).get
    env.blockchainReader.getBlockHeaderByNumber(0).get.hash shouldBe substitute.hash
  }

  "the pure state root" should "write no code and build its tries over a throwaway store (forge 2)" taggedAs (
    UnitTest
  ) in {
    given BlockchainConfig = Sepolia()
    val data = withStorage(allocs.head._2)
    val env = new Env
    val codeHash = ByteString(crypto.kec256(Code))
    env.storagesInstance.storages.evmCodeStorage.get(codeHash) shouldBe None
    GenesisDataLoader.genesisHeader(data)
    // nothing a node stores was touched: the pure function is given no storage at all
    env.storagesInstance.storages.evmCodeStorage.get(codeHash) shouldBe None
    // while the loader does store the code
    loader(env).loadGenesisData(data).get
    env.storagesInstance.storages.evmCodeStorage.get(codeHash) shouldBe Some(ByteString(Code))
  }

  it should "equal the state root the loader stores, and the empty root for an empty alloc" taggedAs (UnitTest) in {
    given BlockchainConfig = Sepolia()
    allocs.foreach { case (_, alloc) =>
      val data = withStorage(alloc)
      ByteString(GenesisDataLoader.pureStateRoot(data)) shouldBe stored(data).stateRoot.value
    }
    ByteString(GenesisDataLoader.pureStateRoot(withStorage(Map.empty))) shouldBe
      ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
  }

  it should "keep the shared storage subtrees whole (removals are dropped, as in the loader)" taggedAs (UnitTest) in {
    // EEST stEIP2930/variedContext: the second account's trie is built over the first's leaf and replaces it. A store
    // that applied removals would delete the first account's storage root, and the state root would still come out
    // right: so the pin is the equality with the loader over exactly that shape.
    given BlockchainConfig = Sepolia()
    val data = withStorage(
      Map(
        address(0xf114) -> account(1, None, None, Some(Map(UInt256(0) -> UInt256(0x0bad)))),
        address(0xf115) -> account(
          1,
          None,
          None,
          Some(Map(UInt256(0) -> UInt256(0x0bad), UInt256(0x60a7) -> UInt256(0xdead)))
        )
      )
    )
    GenesisDataLoader.genesisHeader(data) shouldBe stored(data)
  }
