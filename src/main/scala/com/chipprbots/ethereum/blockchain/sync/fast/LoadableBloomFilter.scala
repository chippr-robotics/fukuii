package com.chipprbots.ethereum.blockchain.sync.fast

import cats.effect.IO
import fs2.Stream

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel

import com.chipprbots.ethereum.blockchain.sync.fast.LoadableBloomFilter.BloomFilterLoadingResult
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

class LoadableBloomFilter[A](bloomFilter: BloomFilter[A], source: Stream[IO, Either[IterationError, A]]) {
  val loadFromSource: IO[BloomFilterLoadingResult] =
    source
      .fold(BloomFilterLoadingResult()) { (s, e) =>
        e match {
          case Left(value) => s.copy(error = Some(value))
          case Right(value) =>
            bloomFilter.put(value)
            s.copy(writtenElements = s.writtenElements + 1)
        }
      }
      .compile
      .lastOrError
      .memoize
      .flatMap(identity)

  def put(elem: A): Boolean = bloomFilter.put(elem)

  def mightContain(elem: A): Boolean = bloomFilter.mightContain(elem)

  def approximateElementCount: Long = bloomFilter.approximateElementCount()
}

object LoadableBloomFilter {
  def apply[A](expectedSize: Int, loadingSource: Stream[IO, Either[IterationError, A]])(implicit
      f: Funnel[A]
  ): LoadableBloomFilter[A] =
    new LoadableBloomFilter[A](BloomFilter.create[A](f, expectedSize), loadingSource)

  case class BloomFilterLoadingResult(writtenElements: Long, error: Option[IterationError])
  object BloomFilterLoadingResult {
    def apply(): BloomFilterLoadingResult = new BloomFilterLoadingResult(0, None)

    def apply(ex: Throwable): BloomFilterLoadingResult = new BloomFilterLoadingResult(0, Some(IterationError(ex)))
  }
}
