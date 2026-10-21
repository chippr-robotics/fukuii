package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ChildFactories
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T012: the `ChildFactories` seam builds exactly what the controller built before it existed. Each production
  * method forwards every argument by name to the child's `apply` (checked by the compiler), and the spawn sites pass
  * the same argument lists as before, so the only way the seam could change a spawn is a default that differs from the
  * `apply`'s. These tests read both sets of default values (Scala's `<method>$default$<n>` getters) and require them to
  * be equal, position by position.
  */
class ChildFactoriesSpec extends AnyFlatSpec with Matchers:

  /** Parameter position (1-based) -> default value, for every defaulted parameter of `method` on `target`. */
  private def defaults(target: AnyRef, method: String): Map[Int, Any] =
    val prefix = s"$method$$default$$"
    target.getClass.getMethods.toSeq
      .filter(m => m.getName.startsWith(prefix) && m.getParameterCount == 0)
      .map(m => m.getName.stripPrefix(prefix).toInt -> m.invoke(target))
      .toMap

  private val production = ChildFactories.production

  "ChildFactories.production" should "use AccountRangeCoordinator.apply's defaults (spec 016 T012)" taggedAs UnitTest in {
    val seam = defaults(production, "accountRangeCoordinator")
    seam should not be empty
    seam shouldBe defaults(AccountRangeCoordinator, "apply")
  }

  it should "use StorageRangeCoordinator.apply's defaults (spec 016 T012)" taggedAs UnitTest in {
    val seam = defaults(production, "storageRangeCoordinator")
    seam should not be empty
    seam shouldBe defaults(StorageRangeCoordinator, "apply")
  }

  it should "use TrieNodeHealingCoordinator.apply's defaults (spec 016 T012)" taggedAs UnitTest in {
    val seam = defaults(production, "trieNodeHealingCoordinator")
    seam should not be empty
    seam shouldBe defaults(TrieNodeHealingCoordinator, "apply")
  }

  it should "use ByteCodeCoordinator.apply's defaults (spec 016 T012)" taggedAs UnitTest in {
    val seam = defaults(production, "byteCodeCoordinator")
    seam should not be empty
    seam shouldBe defaults(ByteCodeCoordinator, "apply")
  }

  it should "use ChainDownloader.apply's defaults (spec 016 T012)" taggedAs UnitTest in {
    val seam = defaults(production, "chainDownloader")
    val applyDefaults = defaults(ChainDownloader, "apply")
    seam should not be empty
    seam.keySet shouldBe applyDefaults.keySet
    // `blacklist` (11) builds a fresh blacklist and `nowMs` (15) is a fresh clock lambda on every call, so neither
    // compares equal to itself; check that both build the same kind of thing.
    val freshPerCall = Set(11, 15)
    seam.removedAll(freshPerCall) shouldBe applyDefaults.removedAll(freshPerCall)
    seam(11) shouldBe a[CacheBasedBlacklist]
    applyDefaults(11) shouldBe a[CacheBasedBlacklist]
    seam(15) shouldBe a[Function0[?]]
    applyDefaults(15) shouldBe a[Function0[?]]
  }
