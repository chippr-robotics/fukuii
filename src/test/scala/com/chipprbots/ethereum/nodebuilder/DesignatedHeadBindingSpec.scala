package com.chipprbots.ethereum.nodebuilder

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.EngineApiHttpServer
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType

/** The binding gate that keeps `ConsensusImpl.importToNewBranch`'s PoS arm inert on PoW chains, exercised on the REAL
  * node cake.
  *
  * WHY THIS EXISTS. In production `ConsensusImpl` always receives `Some(designatedHead)`, a `DesignatedHead.LateBound`,
  * on every network — ETC included. The only thing that keeps the PoS arm from overriding the TD rule on ETC is that
  * this holder is never BOUND there: `EngineApiBuilder.bindDesignatedHead()` binds it only when the Engine API is
  * enabled AND `terminal-total-difficulty` is configured. This spec builds `StdNode` itself (the class `App` runs),
  * against every ETC chain config in `blockchains.conf`, and asserts the holder is still unbound after the binder runs.
  *
  * The Engine API is forced ON in every case. That is the worst case for the gate: it removes the first conjunct and
  * leaves the TTD check alone holding the line, which is exactly the property a reviewer needs — "even if someone
  * enables an Engine API on a PoW chain, fork choice stays TD-based".
  *
  * The ETH-family rows are the positive control: same node, same forced-on Engine API, and the holder DOES bind. So a
  * green ETC row means the gate held, not that the binder never ran.
  */
class DesignatedHeadBindingSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks:

  private val allChains: Map[String, BlockchainConfig] = Config.blockchains.blockchains

  private val etcChains: Seq[(String, BlockchainConfig)] =
    allChains.toSeq.filter(_._2.networkType == NetworkType.ETC).sortBy(_._1)

  private val ethChainsWithTtd: Seq[(String, BlockchainConfig)] =
    allChains.toSeq
      .filter { case (_, c) => c.networkType == NetworkType.ETH && c.terminalTotalDifficulty.isDefined }
      .sortBy(_._1)

  /** The production node cake with only two things changed: the chain, and the Engine API forced on. */
  private class NodeOn(chain: BlockchainConfig) extends StdNode(Config) with EphemBlockchainTestSetup:
    override lazy val ioRuntime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
    implicit override def blockchainConfig: BlockchainConfig = chain
    override lazy val engineApiConfig: EngineApiHttpServer.Config = EngineApiHttpServer.Config(enabled = true)

  "bindDesignatedHead" should "leave the holder UNBOUND on every ETC chain, even with the Engine API forced on" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Mainnet and Mordor at minimum; any ETC chain added to blockchains.conf later is covered automatically.
    (etcChains.map(_._1) should contain).allOf("etc", "mordor")
    forAll(Table(("chain", "config"), etcChains*)) { (name, config) =>
      val node = new NodeOn(config)
      node.designatedHead.isBound shouldBe false
      node.bindDesignatedHead()
      withClue(
        s"ETC chain '$name': bindDesignatedHead() bound the PoS fork-choice holder. ConsensusImpl.importToNewBranch " +
          "would then follow a CL-designated head instead of the TD rule on a Proof-of-Work chain. "
      )(node.designatedHead.isBound shouldBe false)
      node.designatedHead.headBlockHash shouldBe None
    }
  }

  it should "BIND the holder on ETH-family chains with a terminal total difficulty — the positive control" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    ethChainsWithTtd should not be empty
    forAll(Table(("chain", "config"), ethChainsWithTtd*)) { (name, config) =>
      val node = new NodeOn(config)
      node.bindDesignatedHead()
      withClue(s"ETH chain '$name' has a TTD and the Engine API on, so the holder must bind: ")(
        node.designatedHead.isBound shouldBe true
      )
    }
  }
