package com.chipprbots.ethereum

import cats.~>
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Sync
import cats.effect.implicits._
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.scalactic.TypeCheckedTripleEquals
import org.scalatest._
import org.scalatest.diagrams.Diagrams
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.freespec.AsyncFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

trait SpecBase extends TypeCheckedTripleEquals with Diagrams with Matchers { self: AsyncTestSuite =>

  override val executionContext = ExecutionContext.global
  implicit val runtime: IORuntime = IORuntime.global

  def customTestCaseResourceM[M[_]: Async, T](
      fixture: Resource[M, T]
  )(theTest: T => M[Assertion]): Future[Assertion] = {
    // In Cats Effect 3, we need to explicitly handle the conversion to IO
    // Since in practice M is always IO, we can safely cast here
    fixture.use(theTest).asInstanceOf[IO[Assertion]].unsafeToFuture()
  }

  def customTestCaseM[M[_]: Async, T](fixture: => T)(theTest: T => M[Assertion]): Future[Assertion] =
    customTestCaseResourceM(Resource.pure[M, T](fixture))(theTest)

  def testCaseM[M[_]: Async](theTest: => M[Assertion]): Future[Assertion] = customTestCaseM(())(_ => theTest)

  def testCase(theTest: => Assertion): Future[Assertion] = testCaseM[IO](IO(theTest))
}

trait FlatSpecBase extends AsyncFlatSpecLike with SpecBase {}

trait FreeSpecBase extends AsyncFreeSpecLike with SpecBase {}

trait WordSpecBase extends AsyncWordSpecLike with SpecBase {}

trait SpecFixtures { self: SpecBase =>
  type Fixture

  def createFixture(): Fixture

  def testCaseM[M[_]: Async](theTest: Fixture => M[Assertion]): Future[Assertion] =
    customTestCaseM(createFixture())(theTest)

  def testCase(theTest: Fixture => Assertion): Future[Assertion] =
    testCaseM[IO]((fixture: Fixture) => IO.pure(theTest(fixture)))
}

trait ResourceFixtures { self: SpecBase =>
  type Fixture

  def fixtureResource: Resource[IO, Fixture]

  def testCaseM[M[_]: Async](theTest: Fixture => M[Assertion]): Future[Assertion] = {
    // In practice M is always IO, so we can use identity transformation
    customTestCaseResourceM(fixtureResource.asInstanceOf[Resource[M, Fixture]])(theTest)
  }

  /** IO-specific method to avoid type inference issues in [[testCaseM]]
    */
  def testCaseT(theTest: Fixture => IO[Assertion]): Future[Assertion] =
    customTestCaseResourceM[IO, Fixture](fixtureResource)(theTest)

  def testCase(theTest: Fixture => Assertion): Future[Assertion] =
    customTestCaseResourceM[IO, Fixture](fixtureResource)(fixture => IO.pure(theTest(fixture)))
}
