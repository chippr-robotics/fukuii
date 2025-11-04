package com.chipprbots.scalanet.peergroup

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultPromise
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NettyFutureUtilsSpec extends AnyFlatSpec with Matchers {

  behavior of "NettyFutureUtils"

  it should "handle already completed futures" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    try {
      val executor = eventLoopGroup.next()
      val promise = new DefaultPromise[String](executor)
      promise.setSuccess("test-value")

      val result = NettyFutureUtils.fromNettyFuture(IO.pure(promise)).unsafeRunSync()
      result shouldBe "test-value"
    } finally {
      eventLoopGroup.shutdownGracefully().sync()
    }
  }

  it should "handle futures that complete normally" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    try {
      val executor = eventLoopGroup.next()
      val promise = new DefaultPromise[Int](executor)
      
      // Complete the promise asynchronously
      executor.execute(() => promise.setSuccess(42))

      val result = NettyFutureUtils.fromNettyFuture(IO.pure(promise)).unsafeRunSync()
      result shouldBe 42
    } finally {
      eventLoopGroup.shutdownGracefully().sync()
    }
  }

  it should "handle futures when event loop is shutting down" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    val executor = eventLoopGroup.next()
    val promise = new DefaultPromise[String](executor)
    
    // Shut down the event loop immediately
    eventLoopGroup.shutdownGracefully()
    
    // Complete the promise after shutdown
    promise.setSuccess("completed-after-shutdown")
    
    // This should not throw RejectedExecutionException
    val result = NettyFutureUtils.fromNettyFuture(IO.pure(promise)).unsafeRunSync()
    result shouldBe "completed-after-shutdown"
  }

  it should "handle toTask with already completed futures during shutdown" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    val executor = eventLoopGroup.next()
    
    // Create a promise that will complete
    val promise = new DefaultPromise[Void](executor)
    promise.setSuccess(null)
    
    // Shut down the event loop
    eventLoopGroup.shutdownGracefully()
    
    // This should not throw RejectedExecutionException
    noException should be thrownBy {
      NettyFutureUtils.toTask(promise).unsafeRunSync()
    }
  }

  it should "handle toTask when event loop shuts down before listener is added" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    val executor = eventLoopGroup.next()
    
    // Create a promise that is not yet complete
    val promise = new DefaultPromise[Void](executor)
    
    // Shut down the event loop immediately
    eventLoopGroup.shutdownGracefully()
    
    // Complete the promise after shutdown
    promise.setSuccess(null)
    
    // This should not throw RejectedExecutionException
    noException should be thrownBy {
      NettyFutureUtils.toTask(promise).unsafeRunSync()
    }
  }

  it should "handle failed futures" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    try {
      val executor = eventLoopGroup.next()
      val promise = new DefaultPromise[String](executor)
      val testException = new RuntimeException("test failure")
      promise.setFailure(testException)

      val caught = intercept[RuntimeException] {
        NettyFutureUtils.fromNettyFuture(IO.pure(promise)).unsafeRunSync()
      }
      caught.getMessage shouldBe "test failure"
    } finally {
      eventLoopGroup.shutdownGracefully().sync()
    }
  }

  it should "handle cancelled futures" in {
    val eventLoopGroup = new NioEventLoopGroup(1)
    try {
      val executor = eventLoopGroup.next()
      val promise = new DefaultPromise[String](executor)
      promise.cancel(true)

      // Cancelled futures should not invoke the callback (they are ignored)
      // so the IO should hang. We'll use timeout to detect this behavior.
      // Using 500ms timeout to be reliable across different environments
      import scala.concurrent.duration._
      val result = NettyFutureUtils.fromNettyFuture(IO.pure(promise))
        .timeout(500.milliseconds)
        .attempt
        .unsafeRunSync()
      
      result.isLeft shouldBe true
    } finally {
      eventLoopGroup.shutdownGracefully().sync()
    }
  }
}
