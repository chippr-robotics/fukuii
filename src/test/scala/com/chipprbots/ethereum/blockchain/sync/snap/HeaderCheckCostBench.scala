package com.chipprbots.ethereum.blockchain.sync.snap

import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.BlockHeader

/** Cost of the SNAP header download's checks on proof-of-work chains, to choose the seal sample window: Ethash cache
  * builds per epoch, one light verification (`hashimotoLight`), and the parent rules without the seal, on real Mordor
  * headers. Not a test. Run: `sbt "Test/runMain com.chipprbots.ethereum.blockchain.sync.snap.HeaderCheckCostBench"`.
  */
object HeaderCheckCostBench:

  private def timeMs[A](body: => A): (A, Double) =
    val start = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - start) / 1e6)

  def main(@annotation.unused args: Array[String]): Unit =
    import PowHeaderFixtures.*
    given com.chipprbots.ethereum.utils.BlockchainConfig = mordorConfig

    // 1. Ethash cache build, by epoch (cache size grows linearly with the epoch).
    val epochs = Seq(0L, 100L, 200L, 289L, 389L, 428L)
    val cacheMs = epochs.map { epoch =>
      // The seed for an epoch: any block in it, under the pre-ECIP-1099 rule (the seed depends on the epoch only).
      val block = epoch * EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
      val seed = EthashUtils.seed(block, Long.MaxValue)
      EthashUtils.makeCache(epoch, seed) // JIT warm-up for the first; real cost below
      val (_, ms) = timeMs(EthashUtils.makeCache(epoch, seed))
      println(f"makeCache epoch=$epoch%4d size=${EthashUtils.cacheSize(epoch) / 1048576.0}%6.1f MiB: $ms%8.1f ms")
      epoch -> ms
    }

    // 2. One light verification, warm, at a low and a high epoch.
    def lightMs(header: BlockHeader, reps: Int): Double =
      val ecip = mordorConfig.forkBlockNumbers.ecip1099BlockNumber.toLong
      val n = header.number.value.toLong
      val epoch = EthashUtils.epoch(n, ecip)
      val cache = EthashUtils.makeCache(epoch, EthashUtils.seed(n, ecip))
      val sealHash = kec256(BlockHeader.getEncodedWithoutNonce(header))
      val nonce = header.nonce.toArray
      val dag = EthashUtils.dagSize(epoch)
      (1 to 50).foreach(_ => EthashUtils.hashimotoLight(sealHash, nonce, dag, cache))
      val (_, ms) = timeMs((1 to reps).foreach(_ => EthashUtils.hashimotoLight(sealHash, nonce, dag, cache)))
      ms / reps
    val lightLow = lightMs(fromGenesis(100), 500)
    val lightHigh = lightMs(recent.head, 500)
    println(
      f"hashimotoLight (warm): epoch 0 $lightLow%.3f ms, epoch ${EthashUtils.epoch(recent.head.number.value.toLong, 2520000L)} $lightHigh%.3f ms"
    )

    // 3. Parent rules without the seal (difficulty, timestamp, gas, number, extra data), warm.
    val pairs = (1 until fromGenesis.size).map(i => (fromGenesis(i), fromGenesis(i - 1)))
    (1 to 20).foreach(_ => pairs.foreach { case (h, p) => PoWBlockHeaderValidator.validateParentRules(h, p) })
    val reps = 200
    val (_, rulesTotal) = timeMs(
      (1 to reps).foreach(_ => pairs.foreach { case (h, p) => PoWBlockHeaderValidator.validateParentRules(h, p) })
    )
    val rulesUs = rulesTotal * 1000 / (reps * pairs.size)
    println(f"parent rules without seal: $rulesUs%.2f us per header")

    // Projection: linear fit of cache build time against epoch.
    val (xs, ys) = (cacheMs.map(_._1.toDouble), cacheMs.map(_._2))
    val mx = xs.sum / xs.size
    val my = ys.sum / ys.size
    val slope = xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum / xs.map(x => (x - mx) * (x - mx)).sum
    val intercept = my - slope * mx
    def cacheBuildMs(epochs: Seq[Long]): Double = epochs.map(e => intercept + slope * e).sum

    final case class Chain(name: String, head: Long, ecip1099: Long)
    val chains = Seq(Chain("ETC mainnet", 25_700_000L, 11_700_000L), Chain("Mordor", 17_360_000L, 2_520_000L))
    val perRequest = 1024
    for chain <- chains do
      val preEpochs = 0L to ((chain.ecip1099 - 1) / 30000)
      val postEpochs = (chain.ecip1099 / 60000) to (chain.head / 60000)
      // Epoch numbers repeat after ECIP-1099 (it halves them), and only two caches are kept: each one is built again.
      val allEpochs = preEpochs ++ postEpochs
      val cacheS = cacheBuildMs(allEpochs) / 1000
      val rulesS = chain.head * rulesUs / 1e6
      println(
        f"${chain.name}: ${chain.head}%,d headers, ${allEpochs.size} epoch caches (${cacheS / 60}%.1f min), parent rules ${rulesS / 60}%.1f min"
      )
      for k <- Seq(32, 64, 100, 128, 256) do
        val sealsPerResponse = math.ceil(perRequest.toDouble / k) + 1
        val seals = chain.head.toDouble / perRequest * sealsPerResponse + k
        val sealS = seals * lightHigh / 1000
        println(
          f"  K=$k%3d: ${seals / 1000}%7.1f k seals, ${sealS / 60}%5.1f min; total extra ${(sealS + cacheS + rulesS) / 60}%5.1f min"
        )
