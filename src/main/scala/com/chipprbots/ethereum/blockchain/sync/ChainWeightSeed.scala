package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.Hex

/** The operator's `-Dfukuii.seed-chain-weights=HASH:TD,...` (#1654, H6(2)): a trusted weight for a BASE, a block whose
  * parent has no plausible stored weight (a SNAP or checkpoint pivot whose headers below are not in). The seeded weight
  * is written, and every canonical block above it gets its parent's weight plus its own difficulty, so the seed leaves
  * no jump.
  *
  * A seed where the parent's weight is plausible is refused. That weight was derived from the chain below, and
  * overwriting the block above it is a jump that every fork branching below is weighed across. It is also what the
  * timed calibration's derivable arm reverts 30 s later. To correct such a chain, let `ChainWeightReconcile` re-derive
  * it from genesis.
  *
  * An accepted seed takes the chain-weight marker away and drops `ChainWeightReconcile`'s saved place: the value is
  * trusted, not proven, so the next pass checks every weight from genesis once the headers are in, the seeded one
  * included. On a chain that was PoS from genesis (terminal TD 0) every seed is refused: the true weight is the genesis
  * weight at every height, the pass derives it, and only a wrong seed could pass the checks here.
  */
object ChainWeightSeed:

  /** The system property. */
  val Property: String = "fukuii.seed-chain-weights"

  enum Outcome:
    case Applied(number: BigInt, hash: ByteString, td: BigInt, descendants: Int)
    case Refused(entry: String, reason: String)

  /** One entry per `HASH:TD` (hash hex with or without `0x`, TD decimal). */
  def parse(spec: String): Seq[Either[Outcome.Refused, (ByteString, BigInt)]] =
    spec.split(",").toSeq.map(_.trim).filter(_.nonEmpty).map { entry =>
      entry.split(":") match
        case Array(hashHex, tdStr) =>
          (
            Try(ByteString(Hex.decode(hashHex.trim.stripPrefix("0x")))).toOption.filter(_.size == 32),
            Try(BigInt(tdStr.trim)).toOption.filter(_ > 0)
          ) match
            case (Some(hash), Some(td)) => Right(hash -> td)
            case (None, _)              => Left(Outcome.Refused(entry, "the hash is not 32 bytes of hex"))
            case (_, None)              => Left(Outcome.Refused(entry, "the TD is not a positive decimal number"))
        case _ => Left(Outcome.Refused(entry, "expected HASH:TD"))
    }

  /** Every entry of `spec`, in order. The caller holds the import lock and the chain insert lock exclusively, and runs
    * it inside `ChainWeightReconcile.Epoch.supersede`.
    *
    * Where the value comes from: a client that still reports total difficulty, for the base block (the lowest block
    * with a weight, usually the SNAP pivot), not the head. core-geth does; go-ethereum since 1.15 returns no
    * `totalDifficulty`. For example:
    * {{{
    * curl -s localhost:8545 -H 'Content-Type: application/json' \
    *   -d '{"jsonrpc":"2.0","id":1,"method":"eth_getBlockByNumber","params":["0x<pivot hex>",false]}' \
    *   | python3 -c 'import json,sys; b=json.load(sys.stdin)["result"]; print(b["hash"]+":"+str(int(b["totalDifficulty"],16)))'
    * }}}
    */
  def apply(
      spec: String,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage,
      posFromGenesis: Boolean,
      batchSize: Int = 256
  ): Seq[Outcome] =
    parse(spec).map {
      // A chain PoS from genesis (terminal TD 0): every weight is the genesis weight, which the plausibility floor
      // cannot accept above height 0, and a seed has nothing to add to it (beacon W4).
      case Right((hash, td)) if posFromGenesis =>
        Outcome.Refused(
          s"${Hex.toHexString(hash.toArray)}:$td",
          "this chain is PoS from genesis (terminal total difficulty 0): every block's weight is the genesis weight, " +
            "and the chain-weight pass derives it"
        )
      case Right((hash, td)) => seed(hash, td, reader, writer, appState, batchSize)
      case Left(refused)     => refused
    }

  private def seed(
      hash: ByteString,
      td: BigInt,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage,
      batchSize: Int
  ): Outcome =
    val entry = s"${Hex.toHexString(hash.toArray)}:$td"
    reader.getBlockHeaderByHash(BlockHash(hash)) match
      case None => Outcome.Refused(entry, "no header is stored for this hash")
      case Some(header) =>
        val number = header.number.value
        val genesisWeight = HeadChainWeight.genesisWeight(reader)
        lazy val parentPlausible = reader
          .getChainWeightByHash(header.parentHash)
          .exists(w => HeadChainWeight.isPlausible(number - 1, w.totalDifficulty.value, genesisWeight))
        if number == 0 then Outcome.Refused(entry, "the genesis weight is its own difficulty and is never seeded")
        else if !reader.getBlockHeaderByNumber(number).exists(_.hash.value == hash) then
          Outcome.Refused(entry, s"block $number is not on the canonical chain")
        else if !HeadChainWeight.isPlausible(number, td, genesisWeight) then
          Outcome.Refused(
            entry,
            s"$td is below ${HeadChainWeight.floor(number, genesisWeight)}, the least weight any real block $number has"
          )
        else if parentPlausible then
          Outcome.Refused(
            entry,
            s"block ${number - 1}, its parent, has a plausible weight, so a seed here would leave a jump; a seed " +
              "is for a base whose parent has none. The reconcile pass re-derives every weight from genesis"
          )
        else
          val descendants = derive(number, hash, td, reader, batchSize)
          writer
            .storeChainWeights((BlockHash(hash) -> ChainWeight.totalDifficultyOnly(td)) +: descendants)
            .and(appState.clearChainWeightInvariant())
            .and(appState.removeChainWeightReconcileProgress())
            .commitSync()
          Outcome.Applied(number, hash, td, descendants.size)

  /** Each canonical block above `number`, up to the best block, with its parent's weight plus its own difficulty. Stops
    * at the first height with no header, or one that does not link to the block below.
    */
  private def derive(
      number: BigInt,
      hash: ByteString,
      td: BigInt,
      reader: BlockchainReader,
      batchSize: Int
  ): Vector[(BlockHash, ChainWeight)] =
    val best = reader.getBestBlockNumber
    var out = Vector.empty[(BlockHash, ChainWeight)]
    var prevHash = hash
    var weight = td
    var n = number + 1
    var linked = true
    while linked && n <= best do
      val numbers = (n to (n + batchSize - 1).min(best)).toVector
      val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
      val headers = reader.getBlockHeadersByHashesNoFill(hashes)
      var i = 0
      while linked && i < hashes.size do
        headers(i) match
          case Some(h) if h.parentHash.value == prevHash =>
            weight += h.difficulty.value
            out :+= BlockHash(hashes(i)) -> ChainWeight.totalDifficultyOnly(weight)
            prevHash = hashes(i)
            i += 1
          case _ => linked = false
      if hashes.size < numbers.size then linked = false
      n = numbers.last + 1
    out
