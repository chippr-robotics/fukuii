package com.chipprbots.ethereum.vm

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import scala.io.Source

import com.typesafe.config.ConfigFactory
import org.scalatest.Assertions

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.MockWorldState.*

/** Shared construction for spec 021's S0 pins (OpCodeEdgeRowsSpec, EtcOpCodeEdgeRowsSpec, OpCodeTableGoldenSpec,
  * TraceGoldenSpec).
  *
  * The pins run one instruction, or one frame, on a state built here, so the later slices (S3's word stack, S4's
  * mutable memory) migrate the stack and memory construction in this one file, never the expected values.
  */
// scalastyle:off magic.number
object PinHarness:

  /** The account whose code runs, and its caller. */
  val Owner: Address = Address(0xc0de)
  val Caller: Address = Address(0xca11)

  /** An existing, empty-code account with a balance: a CALL target that runs nothing and returns all its gas. */
  val Eoa: Address = Address(0xe0a)

  val OwnerBalance: UInt256 = UInt256(BigInt(10).pow(18))

  /** Owner (with `code`), Caller and Eoa, each with a balance. */
  def world(code: ByteString = ByteString.empty): MockWorldState =
    MockWorldState()
      .saveAccount(Owner, Account.empty().increaseBalance(OwnerBalance))
      .saveAccount(Caller, Account.empty().increaseBalance(OwnerBalance))
      .saveAccount(Eoa, Account.empty().increaseBalance(UInt256(1)))
      .saveCode(Owner, code)

  /** A stack holding `words` with the FIRST on top: the order an instruction pops its operands. */
  def operands(words: UInt256*): Stack = Stack.empty().push(words.reverse)

  /** The stack's words, top first. */
  def words(stack: Stack): Seq[UInt256] = stack.toSeq

  /** Memory holding `bytes` from offset 0. */
  def memoryOf(bytes: ByteString): Memory = Memory.empty.store(UInt256.Zero, bytes)

  def header(number: BigInt, timestamp: Long = 0L): BlockHeader =
    BlockFixtures.ValidBlock.header.copy(
      number = BlockNumber(number),
      unixTimestamp = Timestamp(timestamp),
      extraFields = HefEmpty
    )

  /** A frame of `Owner` at depth 0, about to run `code` from pc 0 with `stack` and `memory`. */
  def state(
      config: EvmConfig,
      header: BlockHeader,
      stack: Stack = Stack.empty(),
      gas: BigInt = 1_000_000,
      world: MockWorldState = PinHarness.world(),
      originalWorld: Option[MockWorldState] = None,
      code: ByteString = ByteString.empty,
      inputData: ByteString = ByteString.empty,
      memory: Memory = Memory.empty,
      returnData: ByteString = ByteString.empty,
      blobHashes: Seq[ByteString] = Seq.empty,
      warmStorage: Set[(Address, StorageKey)] = Set.empty,
      stateGasReservoir: BigInt = 0,
      tracer: Option[ExecutionTracer] = None
  ): PS =
    val context: PC = ProgramContext(
      callerAddr = Caller,
      originAddr = Caller,
      recipientAddr = Some(Owner),
      gasPrice = UInt256.Zero,
      startGas = gas,
      inputData = inputData,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = config,
      originalWorld = originalWorld.getOrElse(world),
      warmAddresses = Set.empty,
      warmStorage = warmStorage,
      blobVersionedHashes = blobHashes,
      stateGasReservoir = stateGasReservoir
    )
    val env = ExecEnv(context, code, Owner)
    ProgramState(new VM[MockWorldState, MockStorage](tracer), context, env)
      .withStack(stack)
      .withMemory(memory)
      .withReturnData(returnData)

  // ── ETH configs, as `forBlock` selects them from the shipped chain configs ──

  private def shippedChain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig(s"fukuii.blockchains.$name"))

  lazy val ethMainnet: BlockchainConfig = shippedChain("eth")
  lazy val sepolia: BlockchainConfig = shippedChain("sepolia")
  lazy val plataberget: BlockchainConfig = shippedChain("plataberget")

  /** A post-merge ETH mainnet block and the activation timestamps of its forks (eth-chain.conf). */
  val EthPostMergeBlock: BigInt = 24_000_000
  def ethTimestamp(fork: String): Long = fork match
    case "shanghai" => ethMainnet.forkTimestamps.shanghaiTimestamp.get
    case "cancun"   => ethMainnet.forkTimestamps.cancunTimestamp.get
    case "prague"   => ethMainnet.forkTimestamps.pragueTimestamp.get
    case "osaka"    => ethMainnet.forkTimestamps.osakaTimestamp.get
    case other      => throw new IllegalArgumentException(other)

  /** ETH mainnet at Osaka: the config and a header at its activation timestamp. */
  lazy val ethOsaka: (EvmConfig, BlockHeader) =
    val ts = ethTimestamp("osaka")
    (EvmConfig.forBlock(EthPostMergeBlock, Timestamp(ts), ethMainnet), header(EthPostMergeBlock, ts))

  /** ETH mainnet at Cancun. */
  lazy val ethCancun: (EvmConfig, BlockHeader) =
    val ts = ethTimestamp("cancun")
    (EvmConfig.forBlock(EthPostMergeBlock, Timestamp(ts), ethMainnet), header(EthPostMergeBlock, ts))

  /** Sepolia at Amsterdam (sepolia-chain.conf `amsterdam-timestamp`): the only shipped ETH chain with Amsterdam on a
    * public schedule besides Platåberget.
    */
  lazy val sepoliaAmsterdam: (EvmConfig, BlockHeader) =
    val ts = sepolia.forkTimestamps.amsterdamTimestamp.get
    val block = BigInt(10_000_000)
    (EvmConfig.forBlock(block, Timestamp(ts), sepolia), header(block, ts))

  // ── Golden files ──

  /** Compares `actual` with the test resource `resource` (a path under src/test/resources), line by line.
    *
    * On a difference, or if the resource does not exist, the actual text is written under `target/spec021-actual/` for
    * inspection, and the test fails at the first differing line. A golden is never rewritten by a test: a slice whose
    * code changes a golden is wrong (spec 021 DC-8; tasks.md, Notes).
    */
  def assertGolden(resource: String, actual: String): Unit =
    val expected = Option(getClass.getResourceAsStream("/" + resource)).map { in =>
      val source = Source.fromInputStream(in, StandardCharsets.UTF_8.name)
      try source.mkString
      finally source.close()
    }
    compareText(resource, expected, actual)

  /** As [[assertGolden]], against a literal held in the spec itself; `name` names the file the actual text is written
    * to under `target/spec021-actual/` on a difference.
    */
  def assertSameText(name: String, expected: String, actual: String): Unit =
    compareText(name, Some(expected), actual)

  private def compareText(resource: String, expected: Option[String], actual: String): Unit =
    if !expected.contains(actual) then
      val out: Path = Paths.get("target", "spec021-actual").resolve(resource)
      Files.createDirectories(out.getParent)
      Files.write(out, actual.getBytes(StandardCharsets.UTF_8))
      expected match
        case None => Assertions.fail(s"golden resource $resource is missing; the actual output is in $out")
        case Some(exp) =>
          val e = exp.linesIterator.toVector
          val a = actual.linesIterator.toVector
          val i = e.indices.find(i => i >= a.size || e(i) != a(i)).getOrElse(e.size)
          val shown = (s: Vector[String]) => if i < s.size then s(i) else "<end of text>"
          Assertions.fail(
            s"$resource differs at line ${i + 1}:\n  expected: ${shown(e)}\n  actual:   ${shown(a)}\n" +
              s"(the actual output is in $out)"
          )
