package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.FiniteDuration

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.jsonrpc.EthFilterService._
import com.chipprbots.ethereum.jsonrpc.{FilterManager => FM}
import com.chipprbots.ethereum.utils.FilterConfig
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future

class EthFilterServiceSpec
    extends TestKit(ActorSystem("EthFilterServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  it should "handle newFilter request" in new TestSetup {
    val filter: Filter = Filter(None, None, None, Seq.empty)
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newFilter(NewFilterRequest(filter)).unsafeToFuture()
    filterManager.expectMsg(FM.NewLogFilter(None, None, None, Seq.empty))
    filterManager.reply(FM.NewFilterResponse(123))
    res.futureValue shouldEqual Right(NewFilterResponse(123))
  }

  it should "handle newBlockFilter request" in new TestSetup {
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newBlockFilter(NewBlockFilterRequest()).unsafeToFuture()
    filterManager.expectMsg(FM.NewBlockFilter)
    filterManager.reply(FM.NewFilterResponse(123))
    res.futureValue shouldEqual Right(NewFilterResponse(123))
  }

  it should "handle newPendingTransactionFilter request" in new TestSetup {
    val res: Future[Either[JsonRpcError, NewFilterResponse]] =
      ethFilterService.newPendingTransactionFilter(NewPendingTransactionFilterRequest()).unsafeToFuture()
    filterManager.expectMsg(FM.NewPendingTransactionFilter)
    filterManager.reply(FM.NewFilterResponse(123))
    res.futureValue shouldEqual Right(NewFilterResponse(123))
  }

  it should "handle uninstallFilter request" in new TestSetup {
    val res: Future[Either[JsonRpcError, UninstallFilterResponse]] =
      ethFilterService.uninstallFilter(UninstallFilterRequest(123)).unsafeToFuture()
    filterManager.expectMsg(FM.UninstallFilter(123))
    filterManager.reply(FM.UninstallFilterResponse)
    res.futureValue shouldEqual Right(UninstallFilterResponse(true))
  }

  it should "handle getFilterChanges request" in new TestSetup {
    val res: Future[Either[JsonRpcError, GetFilterChangesResponse]] =
      ethFilterService.getFilterChanges(GetFilterChangesRequest(123)).unsafeToFuture()
    filterManager.expectMsg(FM.GetFilterChanges(123))
    val changes: FM.LogFilterChanges = FM.LogFilterChanges(Seq.empty)
    filterManager.reply(changes)
    res.futureValue shouldEqual Right(GetFilterChangesResponse(changes))
  }

  it should "handle getFilterLogs request" in new TestSetup {
    val res: Future[Either[JsonRpcError, GetFilterLogsResponse]] =
      ethFilterService.getFilterLogs(GetFilterLogsRequest(123)).unsafeToFuture()
    filterManager.expectMsg(FM.GetFilterLogs(123))
    val logs: FM.LogFilterLogs = FM.LogFilterLogs(Seq.empty)
    filterManager.reply(logs)
    res.futureValue shouldEqual Right(GetFilterLogsResponse(logs))
  }

  it should "handle getLogs request" in new TestSetup {
    val filter: Filter = Filter(None, None, None, Seq.empty)
    val res: Future[Either[JsonRpcError, GetLogsResponse]] =
      ethFilterService.getLogs(GetLogsRequest(filter)).unsafeToFuture()
    filterManager.expectMsg(FM.GetLogs(None, None, None, Seq.empty))
    val logs: FM.LogFilterLogs = FM.LogFilterLogs(Seq.empty)
    filterManager.reply(logs)
    res.futureValue shouldEqual Right(GetLogsResponse(logs))
  }

  class TestSetup(implicit system: ActorSystem) {
    val filterManager: TestProbe = TestProbe()
    val filterConfig: FilterConfig = new FilterConfig {
      override val filterTimeout: FiniteDuration = Timeouts.normalTimeout
      override val filterManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
    }

    lazy val ethFilterService = new EthFilterService(
      filterManager.ref,
      filterConfig
    )
  }
}
