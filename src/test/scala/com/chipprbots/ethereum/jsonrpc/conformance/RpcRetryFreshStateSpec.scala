package com.chipprbots.ethereum.jsonrpc.conformance

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import org.json4s.*
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.StateRead
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b: a request that executes on a block's state is run again when the state moves under it, and
  * each run takes a state of its own.
  *
  * The second half is what makes the first half worth anything: a method that took its state before it returned its
  * `IO` would be run again on the same stale view, three times. So the real services are driven through the controller
  * on the execution-apis test chain, over a reader whose scratch state goes stale the first times it is taken, and the
  * number of states taken is counted.
  */
class RpcRetryFreshStateSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit

  /** A node whose reader counts the scratch states it hands out, and says the first `staleFor` of them went stale. */
  private class Flaky extends RpcCompatNode:
    val taken = new AtomicInteger
    @volatile var staleFor = 0

    override lazy val blockchainReader: BlockchainReader =
      val s = storagesInstance.storages
      new BlockchainReader(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.stateStorage,
        s.receiptStorage,
        s.appStateStorage,
        s.chainWeightStorage,
        s.blockAccessListStorage,
        s.pathStateStorage
      ):
        override def scratchStateAt(header: BlockHeader): MptStorage =
          val state = super.scratchStateAt(header)
          if taken.incrementAndGet() <= staleFor then
            throw new StaleStateViewException(
              BlockRef(header.hash.value, header.number.value, header.stateRoot.value),
              createdAtEpoch = 2,
              epochNow = 4,
              "the disk moved on"
            )
          state

    /** The answer to `method`, and how many scratch states were taken to give it. */
    def ask(method: String, staleFirst: Int, params: JValue*): (JValue, Int) =
      taken.set(0)
      staleFor = staleFirst
      val request = JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1)))
      val answer = RpcCompatVectors.wire(controller.handleRequest(request).unsafeRunSync())
      (answer, taken.get)

  private lazy val node =
    val flaky = new Flaky
    flaky.importChain()
    flaky

  private lazy val contract: String =
    val alloc = (org.json4s.native.JsonMethods.parse(RpcCompatNode.resource("genesis.json")) \ "alloc")
      .asInstanceOf[JObject]
      .obj
    "0x" + alloc.collectFirst { case (address, account) if (account \ "code") != JNothing => address }.get

  private def call: JObject = JObject("to" -> JString(contract), "data" -> JString("0x"))

  Seq("eth_call", "eth_estimateGas", "eth_createAccessList").foreach { method =>
    s"$method" should "run again on a fresh state each time its state went stale, and answer as if nothing had moved" taggedAs
      UnitTest in {
        val (clean, perRun) = node.ask(method, staleFirst = 0, call, JString("latest"))
        (clean \ "error") shouldBe JNothing
        perRun should be >= 1

        val (afterTwoStale, takenInAll) = node.ask(method, staleFirst = 2, call, JString("latest"))
        afterTwoStale shouldBe clean
        // two runs that met a stale state at once, then a whole run: every run took its own
        takenInAll shouldBe 2 + perRun
      }

    it should "stop after the runs it is allowed, and say the state moved and to ask again" taggedAs UnitTest in {
      val (answer, takenInAll) = node.ask(method, staleFirst = 1000, call, JString("latest"))
      // no execution error: a client takes that for a revert
      (answer \ "error" \ "code") shouldBe JInt(JsonRpcError.StateMoved.code)
      (answer \ "error" \ "message") shouldBe JString(JsonRpcError.StateMoved.message)
      takenInAll shouldBe StateRead.Attempts
    }
  }
