package com.chipprbots.ethereum.consensus
package pow

import java.lang.Integer.remainderUnsigned
import java.util

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.kec512
import com.chipprbots.ethereum.utils.ByteUtils.*

object EthashUtils:

  // Revision number of https://eth.wiki/concepts/ethash/ethash
  final val Revision: Int = 23

  // scalastyle:off magic.number

  // value used in Fowler–Noll–Vo hash function
  final val FNV_PRIME: Int = 0x01000193

  // bytes in word
  final val WORD_BYTES: Int = 4

  // bytes in dataset at genesis
  final val DATASET_BYTES_INIT: Long = BigInt(2).pow(30).toLong

  // dataset growth per epoch
  final val DATASET_BYTES_GROWTH: Long = BigInt(2).pow(23).toLong

  // bytes in cache at genesis
  final val CACHE_BYTES_INIT: Long = BigInt(2).pow(24).toLong

  // cache growth per epoch
  final val CACHE_BYTES_GROWTH: Long = BigInt(2).pow(17).toLong

  // width of mix
  final val MIX_BYTES: Int = 128

  // hash length in bytes
  final val HASH_BYTES: Int = 64

  // number of parents of each dataset element
  final val DATASET_PARENTS: Int = 256

  // number of rounds in cache production
  final val CACHE_ROUNDS: Int = 3

  // number of accesses in hashimoto loop
  final val ACCESSES: Int = 64

  // blocks per epoch (before ecip-1099)
  final val EPOCH_LENGTH_BEFORE_ECIP_1099: Int = 30000

  // blocks per epoch (after ecip-1099)
  final val EPOCH_LENGTH_AFTER_ECIP_1099: Int = 60000

  // scalastyle:on magic.number

  // computes seed for epoch of given blockNumber
  // this also involves the non-ECIP1099 epoch of the first blocks of the
  // ECIP1099 epoch, to make sure every block in the latter results in the same
  // seed being calculated, would there be a cache miss.
  def seed(blockNumber: Long, ecip1099ActivationBlock: Long): ByteString =
    val epochLength = calcEpochLength(blockNumber, ecip1099ActivationBlock)
    val startBlock = (blockNumber / epochLength) * epochLength + 1
    val epoch = startBlock / EPOCH_LENGTH_BEFORE_ECIP_1099

    (BigInt(0) until epoch)
      .foldLeft(ByteString(Hex.decode("00" * 32))) { case (b, _) => kec256(b) }

  private def calcEpochLength(blockNumber: Long, ecip1099ActivationBlock: Long): Long =
    if blockNumber < ecip1099ActivationBlock then EPOCH_LENGTH_BEFORE_ECIP_1099 else EPOCH_LENGTH_AFTER_ECIP_1099

  def epoch(blockNumber: Long, ecip1099ActivationBlock: Long): Long =
    blockNumber / calcEpochLength(blockNumber, ecip1099ActivationBlock)

  def cacheSize(epoch: Long): Long =
    val sz = (CACHE_BYTES_INIT + CACHE_BYTES_GROWTH * epoch) - HASH_BYTES
    highestPrimeBelow(sz, HASH_BYTES)

  def dagSize(epoch: Long): Long =
    val sz = DATASET_BYTES_INIT + DATASET_BYTES_GROWTH * epoch - MIX_BYTES
    highestPrimeBelow(sz, MIX_BYTES)

  @tailrec
  private def highestPrimeBelow(n: Long, len: Long): Long =
    if isPrime(n / len) then n
    else highestPrimeBelow(n - 2 * len, len)

  private def isPrime(n: BigInt): Boolean =
    @tailrec
    def isPrime(n: BigInt, i: BigInt): Boolean =
      (n % i != 0) && ((i * i > n) || isPrime(n, i + 2))

    if n == 2 || n == 3 then true
    else if n < 2 || n % 2 == 0 then false
    else isPrime(n, 3)

  def makeCache(epoch: Long, seed: ByteString): Array[Int] =
    /* watch out, arrays are mutable here */

    val n = (cacheSize(epoch) / HASH_BYTES).toInt
    val s = seed.toArray[Byte]

    val bytes = new Array[Array[Byte]](n)
    bytes(0) = kec512(s)

    (1 until n).foreach { i =>
      bytes(i) = kec512(bytes(i - 1))
    }

    (0 until CACHE_ROUNDS).foreach { _ =>
      (0 until n).foreach { i =>
        val v = remainderUnsigned(getIntFromWord(bytes(i)), n)
        bytes(i) = kec512(xor(bytes((i - 1 + n) % n), bytes(v)))
      }
    }

    val res = new Array[Int](bytes.length * bytes(0).length / 4)
    bytes.indices.foreach { i =>
      val ints = bytesToInts(bytes(i), bigEndian = false)
      System.arraycopy(ints, 0, res, i * ints.length, ints.length)
    }
    res

  def hashimotoLight(
      hashWithoutNonce: Array[Byte],
      nonce: Array[Byte],
      fullSize: Long,
      cache: Array[Int]
  ): EthashProofOfWork =
    hashimoto(hashWithoutNonce, nonce, fullSize, calcDatasetItem(cache, _))

  def hashimoto(
      hashWithoutNonce: Array[Byte],
      nonce: Array[Byte],
      fullSize: Long,
      datasetLookup: Int => Array[Int]
  ): EthashProofOfWork =
    /* watch out, arrays are mutable here */
    val wHash = MIX_BYTES / WORD_BYTES
    val mixHashes = MIX_BYTES / HASH_BYTES
    val mix = new Array[Int](MIX_BYTES / 4)

    val s = bytesToInts(kec512(hashWithoutNonce ++ nonce.reverse), bigEndian = false)

    (0 until mixHashes).foreach { i =>
      System.arraycopy(s, 0, mix, i * s.length, s.length)
    }

    val numFullPages = (fullSize / MIX_BYTES).toInt

    var i = 0
    while i < ACCESSES do
      val p = remainderUnsigned(fnv(i ^ s(0), mix(i % wHash)), numFullPages)
      val newData = new Array[Int](mix.length)
      val off = p * mixHashes

      var j = 0
      while j < mixHashes do
        val lookup = datasetLookup(off + j)
        System.arraycopy(lookup, 0, newData, j * lookup.length, lookup.length)
        j = j + 1

      var k = 0
      while k < mix.length do
        mix(k) = fnv(mix(k), newData(k))
        k = k + 1

      i = i + 1

    val cmix = new Array[Int](mix.length / 4)
    compressMix(mix, cmix)

    EthashProofOfWork(
      mixHash = ByteString(intsToBytes(cmix, bigEndian = false)),
      difficultyBoundary = ByteString(kec256(intsToBytes(s, bigEndian = false) ++ intsToBytes(cmix, bigEndian = false)))
    )

  private def compressMix(mixToCompress: Array[Int], compressedMix: Array[Int]): Unit =
    var l = 0
    while l < mixToCompress.length do
      val fnv1 = fnv(mixToCompress(l), mixToCompress(l + 1))
      val fnv2 = fnv(fnv1, mixToCompress(l + 2))
      val fnv3 = fnv(fnv2, mixToCompress(l + 3))
      compressedMix(l >> 2) = fnv3
      l = l + 4
    ()

  def calcDatasetItem(cache: Array[Int], index: Int): Array[Int] =
    /* watch out, arrays are mutable here */

    val r = HASH_BYTES / WORD_BYTES
    val n = cache.length / r
    val initialMix = util.Arrays.copyOfRange(cache, index % n * r, (index % n + 1) * r)

    initialMix(0) = index ^ initialMix(0)
    val mix = sha512(initialMix, bigEndian = false)
    mixArray(mix, cache, index, r, n)
    sha512(mix, bigEndian = false)

  def sha512(arr: Array[Int], bigEndian: Boolean): Array[Int] =
    var bytesTmp = new Array[Byte](arr.length << 2)
    intsToBytesMut(arr, bytesTmp, bigEndian)
    bytesTmp = kec512(bytesTmp)
    bytesToIntsMut(bytesTmp, arr, bigEndian)
    arr

  private def mixArray(mix: Array[Int], cache: Array[Int], index: Int, r: Int, n: Int): Unit =
    var j = 0
    while j < DATASET_PARENTS do
      val cacheIdx = remainderUnsigned(fnv(index ^ j, mix(j % r)), n)
      val off = cacheIdx * r

      var k = 0
      while k < mix.length do
        mix(k) = fnv(mix(k), cache(off + k))
        k = k + 1
      j = j + 1

  private def fnv(v1: Int, v2: Int): Int =
    (v1 * FNV_PRIME) ^ v2

  /** 2^256: the Ethash target is `2^256 / difficulty`. */
  private val Two256: BigInt = BigInt(1) << 256

  /** Whether a seal's result meets the header's difficulty: the result, read as a 256-bit unsigned big-endian integer,
    * is at most `2^256 / difficulty` (core-geth `consensus/ethash/consensus.go` `verifySeal`: `target := new(big.Int).
    * Div(two256, header.Difficulty)`, rejected if `new(big.Int).SetBytes(result).Cmp(target) > 0`).
    *
    * The arithmetic is unbounded (B-164): a difficulty of 1 has the target 2^256, which no 32-byte field holds, and
    * every result meets it; a difficulty above 2^63 is not cut to a Long. A difficulty of 0 or below is invalid
    * (core-geth `errInvalidDifficulty`, "non-positive difficulty") rather than a division by zero.
    */
  private[pow] def checkDifficulty(blockDifficulty: BigInt, proofOfWork: EthashProofOfWork): Boolean =
    blockDifficulty.signum > 0 &&
      BigInt(1, proofOfWork.difficultyBoundary.toArray[Byte]) <= Two256 / blockDifficulty

  case class EthashProofOfWork(mixHash: ByteString, difficultyBoundary: ByteString)
