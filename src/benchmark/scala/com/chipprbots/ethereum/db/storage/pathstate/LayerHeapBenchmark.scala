package com.chipprbots.ethereum.db.storage.pathstate

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What a forward layer holds on the heap, against what the overlay counts it as (`ForwardLayer.bytes`): the
  * measurement spec 018 calls M3. The overlay's bounds are bounds on that count, so the count must not be under the
  * heap.
  *
  * `sbt "Benchmark / testOnly *LayerHeapBenchmark"`, and read the `[LAYER-HEAP]` lines of the report. Layers of one
  * shape are kept on one datadir; the heap in use is read after a collection, the layers are dropped, and it is read
  * again. The difference is what the layers held: the hash store beside them, which holds every block's nodes too, is
  * the same at both readings. A heap reading after `System.gc()` is not exact, and the figures move by a few per cent
  * from run to run.
  */
class LayerHeapBenchmark extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  private def used(): Long =
    (1 to 6).foreach(_ => System.gc())
    val runtime = Runtime.getRuntime
    runtime.totalMemory - runtime.freeMemory

  /** `layers` sibling blocks of one shape, kept; and what they held, counted and measured. */
  private def measure(shape: String, layers: Int, byHash: Boolean)(block: Int => Seq[Tx]): Double =
    val chain = new Chain(
      CommitSettings(maxForwardLayers = Int.MaxValue, maxForwardBytes = Long.MaxValue, maxLayerBytes = Long.MaxValue)
    )
    var kept = (1 to layers).map(n => chain.on(chain.first)(block(n)*).layered()).toVector
    // the nodes of a layer by their hash, which a layer makes when a read first asks for one
    if byHash then kept.foreach(_.byHash.size)
    val counted = kept.iterator.map(_.bytes).sum
    val positions = kept.iterator.map(_.positions.toLong).sum
    val nodes = kept.iterator.flatMap(_.left.valuesIterator).flatMap(_.next).map(_.length.toLong).sum
    val hashes = kept.map(_.block.hash)
    val held = used()
    hashes.foreach(chain.state.dropLayer)
    kept = Vector.empty
    val heap = held - used()
    info(
      f"[LAYER-HEAP] $shape%-32s by-hash=$byHash%-5s layers=$layers%5d positions=$positions%7d " +
        f"node-bytes=$nodes%9d counted=$counted%10d heap=$heap%10d heap/counted=${heap.toDouble / counted}%.2f " +
        f"heap/position=${heap.toDouble / positions}%.0f beside-the-node/position=${(heap - nodes).toDouble / positions}%.0f"
    )
    heap.toDouble / counted

  "A forward layer" should "be counted as no less than it holds on the heap" taggedAs BenchmarkTest in {
    val ratios = for
      byHash <- Seq(false, true)
      ratio <- Seq(
        measure("one account", 2000, byHash)(n => Seq(account(dave, n % 100 + 1, n))),
        measure("50 slots of a trie of 40", 400, byHash)(n => Seq(slots(bob, (1 to 50).map(i => (n * 100 + i) -> i)*))),
        measure("1000 slots of a new trie", 40, byHash)(n => Seq(slots(eve, (1 to 1000).map(i => (n * 2000 + i) -> i)*))),
        measure("a trie of 40 replaced, 5 slots", 400, byHash)(n =>
          Seq(delete(bob), slots(bob, (1 to 5).map(i => (n * 10 + i) -> i)*))
        )
      )
    yield ratio
    // with room for what a heap reading is off by
    all(ratios) should be < 1.1
  }
