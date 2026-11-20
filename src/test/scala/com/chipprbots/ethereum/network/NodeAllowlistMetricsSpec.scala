package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-051 and the data-model's meters. dev-node's three-node run found no `p2p_allowlist_*` series on
  * `/metrics`: the meters were created when the start-up check first touched the allowlist, before the node configured
  * its metrics, and stayed in the default throwaway registry. They are now looked up in the registry in use at each
  * call, and registered again once metrics are up; the rejections are also summed at INFO each minute.
  */
class NodeAllowlistMetricsSpec extends AnyFlatSpec with Matchers:

  private def registry = Metrics.get().registry

  "The allowlist's meters" should "be the ones of the registry in use, not held from first use" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    NodeAllowlistMetrics.register()
    registry.find("p2p.allowlist.size").gauge() should not be null
    registry.find("p2p.allowlist.reload.failures").counter() should not be null
    val counter = registry.find("p2p.allowlist.rejections").tag("direction", "inbound").counter()
    val before = counter.count()
    NodeAllowlistMetrics.rejected(inbound = true)
    counter.count() - before shouldBe 1.0
    (NodeAllowlistMetrics.InboundRejections should be).theSameInstanceAs(counter)
  }

  it should "report the size it is given" taggedAs (UnitTest, NetworkTest) in {
    NodeAllowlistMetrics.setSize(7)
    registry.find("p2p.allowlist.size").gauge().value() shouldBe 7.0
  }

  "The rejection summary" should "count each direction since the last summary, then start again (FR-051)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val _ = NodeAllowlistMetrics.summary()
    NodeAllowlistMetrics.summary() shouldBe None
    NodeAllowlistMetrics.rejected(inbound = true)
    NodeAllowlistMetrics.rejected(inbound = true)
    NodeAllowlistMetrics.rejected(inbound = false)
    NodeAllowlistMetrics.summary() shouldBe
      Some("P2P node allowlist refused 2 inbound and 1 outbound connection(s) since the last summary")
    NodeAllowlistMetrics.summary() shouldBe None
  }
