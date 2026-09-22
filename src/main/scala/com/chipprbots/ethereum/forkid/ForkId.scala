package com.chipprbots.ethereum.forkid

import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.BigIntExtensionMethods.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils.*
import com.chipprbots.ethereum.utils.Hex

import RLPImplicitConversions.*
import RLPImplicits.given

case class ForkId(hash: BigInt, next: Option[BigInt]):

  def nextDisplay: String = next match
    case None    => "None"
    case Some(n) => ForkId.knownSentinels.get(n).fold(n.toString)(name => s"$n ($name)")

  override def toString(): String =
    s"ForkId(0x${Hex.toHexString(hash.toUnsignedByteArray)}, next=${nextDisplay})"

object ForkId:

  val knownSentinels: Map[BigInt, String] = Map(
    BigInt("1000000000000000000") -> "Olympia"
  )

  def create(genesisHash: ByteString, genesisTimestamp: Long, config: BlockchainConfig)(head: BigInt): ForkId =
    create(genesisHash, genesisTimestamp, config)(head, 0L)

  /** EIP-2124 + EIP-6122: ForkId computation with both block number and timestamp. Block-number forks are compared
    * against `head`, timestamp forks against `headTimestamp`.
    *
    * `genesisTimestamp` is required, not optional: go-ethereum drops every timestamp fork at or before the genesis
    * time, and that cannot be inferred from the config alone. Passing the wrong value produces a checksum that looks
    * plausible and is rejected by every peer, so callers must supply the real genesis header's timestamp.
    */
  def create(genesisHash: ByteString, genesisTimestamp: Long, config: BlockchainConfig)(
      head: BigInt,
      headTimestamp: Long
  ): ForkId =
    val crc = new CRC32()
    crc.update(genesisHash.asByteBuffer)

    val blockForks = gatherBlockForks(config)
    val timestampForks = gatherTimestampForks(config, genesisTimestamp)

    // Process block forks first (sorted), then timestamp forks (sorted)
    val allForks = blockForks.map((_, false)) ++ timestampForks.map((_, true))

    // headTimestamp is a uint64 in a Long. BigInt(headTimestamp) sign-extends, so a head at
    // or above 2^63 would read as negative and NO timestamp fork would count as passed —
    // the node would advertise the checksum of a genesis-era prefix and every honest peer
    // would reject the handshake with "wrong fork ID in status" (EIP-2124/6122).
    val headTimestampUnsigned: BigInt = Timestamp(headTimestamp).toUnsignedBigInt
    val next = allForks.find { case (fork, isTimestamp) =>
      val passed = if isTimestamp then fork <= headTimestampUnsigned else fork <= head
      if passed then crc.update(bigIntToBytes(fork, 8))
      !passed
    }
    new ForkId(crc.getValue(), next.map(_._1))

  // Long.MaxValue is the in-code fallback when a fork config key is missing
  // (BlockchainConfig.fromRawConfig and ForkBlockNumbers.Empty). It must be filtered
  // out of the EIP-2124 fork-id checksum chain as it is not a real fork block.
  // 10^18 is the genesis JSON "not yet scheduled" sentinel. Many ETH-specific fork
  // fields in the ETC config also sit at 10^18 (forks ETC never activated); filtering
  // all 10^18 values from forkBlockNumbers.all prevents those from polluting the fork
  // list. Olympia is re-appended explicitly below when olympiaBlockNumber itself is
  // the sentinel, ensuring ETC/Mordor advertise Olympia as the next fork.
  private val maxBlockSentinel: BigInt = BigInt(Long.MaxValue)
  private val olympiaSentinel: BigInt = BigInt("1000000000000000000")

  def gatherForks(config: BlockchainConfig, genesisTimestamp: Long): List[BigInt] =
    (gatherBlockForks(config) ++ gatherTimestampForks(config, genesisTimestamp)).distinct.sorted

  def gatherBlockForks(config: BlockchainConfig): List[BigInt] =
    val maybeDaoBlock: Option[BigInt] = config.daoForkConfig.flatMap { daoConf =>
      if daoConf.includeOnForkIdList then Some(daoConf.forkBlockNumber)
      else None
    }
    val realForks = (maybeDaoBlock.toList ++ config.forkBlockNumbers.all)
      .filterNot(v => v == 0 || v == olympiaSentinel || v == maxBlockSentinel)
      .distinct
      .sorted
    // Advertise Olympia sentinel as the next fork when not yet scheduled
    val olympiaNext =
      if config.forkBlockNumbers.olympiaBlockNumber == olympiaSentinel then List(olympiaSentinel) else Nil
    realForks ++ olympiaNext

  /** EIP-6122: Timestamp-based forks for post-Merge chains.
    *
    * Forks at or before the genesis timestamp are part of the genesis ruleset, not transitions, and are dropped —
    * go-ethereum's `gatherForks` does exactly this (`for len(forksByTime) > 0 && forksByTime[0] <= genesis`).
    *
    * This previously filtered `== 0`, which is only the same rule on a chain whose genesis timestamp happens to be
    * zero. On a chain with genesis at time 1 and a fork also at 1, we kept the fork and advertised a checksum that had
    * accumulated it while every peer had not — measured on hive's engine suite as
    * `have 0xb9fc74b5 / want 0x4107882a` across the whole `Genesis=1` family.
    */
  def gatherTimestampForks(config: BlockchainConfig, genesisTimestamp: Long): List[BigInt] =
    List(
      config.forkTimestamps.shanghaiTimestamp.map(BigInt(_)),
      config.forkTimestamps.cancunTimestamp.map(BigInt(_)),
      config.forkTimestamps.pragueTimestamp.map(BigInt(_)),
      config.forkTimestamps.osakaTimestamp.map(BigInt(_)),
      config.forkTimestamps.bpo1Timestamp.map(BigInt(_)),
      config.forkTimestamps.bpo2Timestamp.map(BigInt(_)),
      // Amsterdam was absent here while every other timestamp fork was present, so a
      // chain declaring `amsterdamTime` advertised a checksum computed over the forks
      // BEFORE it. Measured against hive's devp2p fixture (genesis
      // 1518c33d…52024c, forks 60/120/180/240/300/360): fukuii sent 0x321a21a2, the
      // checksum of [cancun, prague, osaka], and peers wanted 0x5942bfc2, the checksum
      // of all six. 27 of that suite's 34 failures were the resulting
      // `wrong fork ID in status` handshake rejection.
      config.forkTimestamps.amsterdamTimestamp.map(BigInt(_))
    ).flatten.filterNot(_ <= Timestamp(genesisTimestamp).toUnsignedBigInt).distinct.sorted

  extension (forkId: ForkId)
    def toRLPEncodable: RLPEncodeable =
      import com.chipprbots.ethereum.utils.ByteUtils.*
      val hash: Array[Byte] = bigIntToBytes(forkId.hash, 4).takeRight(4)
      val next: Array[Byte] = bigIntToUnsignedByteArray(forkId.next.getOrElse(BigInt(0))).takeRight(8)
      RLPList(hash, next)

  implicit val forkIdEnc: RLPDecoder[ForkId] = new RLPDecoder[ForkId]:

    def decode(rlp: RLPEncodeable): ForkId = rlp match
      case RLPList(hash, next) =>
        val i = bigIntFromEncodeable(next)
        ForkId(bigIntFromEncodeable(hash), if i == 0 then None else Some(i))
      case _ => throw new RuntimeException("Error when decoding ForkId")
