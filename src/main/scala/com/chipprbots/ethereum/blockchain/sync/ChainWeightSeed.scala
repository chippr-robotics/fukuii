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
  * parent has no plausible stored weight (a SNAP or checkpoint pivot whose headers below are not in). Only the base's
  * weight is written here. The blocks above it are re-derived from it, each its parent's weight plus its own
  * difficulty, by `ChainWeightReconcile`, off the actor's thread: the seed leaves the pass's saved place at the base
  * (`ChainWeightReconcile.seededAt`), and the marker is taken away, so the pass runs.
  *
  * A seed where the parent's weight is plausible is refused. That weight was derived from the chain below, and
  * overwriting the block above it is a jump that every fork branching below is weighed across. It is also what the
  * timed calibration's derivable arm reverts 30 s later. To correct such a chain, let `ChainWeightReconcile` re-derive
  * it from genesis.
  *
  * A seed is trusted, not proven: the seeded pass does not set the marker, and the pass after it checks every weight
  * from genesis once the headers are in, the seeded one included. On a chain that was PoS from genesis
  * ([[HeadChainWeight.posFromGenesis]]: terminal TD at or below the genesis difficulty, e.g. Platåberget's 0) every
  * seed is refused: the true weight is the genesis weight at every height, the pass derives it, and only a wrong seed
  * could pass the checks here.
  *
  * Where the value comes from: a client that still reports total difficulty, for the base block (the lowest block with
  * a weight, usually the SNAP pivot), not the head. core-geth does. go-ethereum dropped `totalDifficulty` from its
  * block RPC in 1.14.11 (#30386). On a post-merge chain no lookup is needed: every block after the merge carries the
  * terminal total difficulty, on Sepolia 17000018015853232 (pinned in `HeadChainWeightSpec`). For example:
  * {{{
  * curl -s localhost:8545 -H 'Content-Type: application/json' \
  *   -d '{"jsonrpc":"2.0","id":1,"method":"eth_getBlockByNumber","params":["0x<pivot hex>",false]}' \
  *   | python3 -c 'import json,sys; b=json.load(sys.stdin)["result"]; print(b["hash"]+":"+str(int(b["totalDifficulty"],16)))'
  * }}}
  */
object ChainWeightSeed:

  /** The system property. */
  val Property: String = "fukuii.seed-chain-weights"

  enum Outcome:
    case Applied(number: BigInt, hash: ByteString, td: BigInt)
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

  /** Every entry of `spec`, in order. The caller holds the chain insert lock exclusively and runs it inside
    * `ChainWeightReconcile.Epoch.supersede`. Each accepted seed is one small batch: the anchor weight, the marker taken
    * away, and the pass's saved place set at the base. `posFromGenesis` is [[HeadChainWeight.posFromGenesis]] for the
    * configured terminal TD.
    */
  def apply(
      spec: String,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage,
      posFromGenesis: Boolean
  ): Seq[Outcome] =
    parse(spec).map {
      // A chain PoS from genesis (terminal TD at or below the genesis difficulty): every weight is the genesis weight,
      // which the plausibility floor cannot accept above height 0, and a seed has nothing to add to it (beacon W4).
      case Right((hash, td)) if posFromGenesis =>
        Outcome.Refused(
          s"${Hex.toHexString(hash.toArray)}:$td",
          "this chain is PoS from genesis (terminal total difficulty at or below the genesis difficulty): every " +
            "block's weight is the genesis weight, and the chain-weight pass derives it"
        )
      case Right((hash, td)) => seed(hash, td, reader, writer, appState)
      case Left(refused)     => refused
    }

  private def seed(
      hash: ByteString,
      td: BigInt,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage
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
          val base = ChainWeightReconcile.Position(number, hash, td)
          writer
            .storeChainWeight(BlockHash(hash), ChainWeight.totalDifficultyOnly(td))
            .and(appState.clearChainWeightInvariant())
            .and(
              appState.putChainWeightReconcileProgress(ChainWeightReconcile.encode(ChainWeightReconcile.seededAt(base)))
            )
            .commitSync()
          Outcome.Applied(number, hash, td)
