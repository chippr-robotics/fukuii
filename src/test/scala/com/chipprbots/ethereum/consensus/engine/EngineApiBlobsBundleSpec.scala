package com.chipprbots.ethereum.consensus.engine

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import ethereum.ckzg4844.CKZG4844JNI

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.KzgCellProofs
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** The blobsBundle a built payload carries, and when EIP-7594 cell proofs are computed for it.
  *
  * Cell proofs cost one c-kzg `computeCellsAndKzgProofs` per blob, and only a BlobsBundleV2 reader uses them
  * (engine_getPayloadV5, Osaka onwards; the testing_* namespace). Computing them for every Cancun/Prague payload made
  * each 6-blob build take ~4.3 s on hive `In-Order Consecutive Payload Execution (Cancun)`, and ten such builds ran the
  * test out of its 60 s budget.
  */
class EngineApiBlobsBundleSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  override def beforeAll(): Unit = KzgTestSetup.ensureLoaded()

  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // buildBlobsBundle reads nothing but its arguments.
  private val service = new EngineApiService(null, null, null, null, None)(null, null)

  private val zeroBlob = Array.fill[Byte](CKZG4844JNI.BYTES_PER_BLOB)(0) // all-zero field elements: a valid blob
  private val commitment = Array.fill[Byte](48)(0xc0.toByte) // carried verbatim, never checked here
  private val proof = Array.fill[Byte](48)(0xc1.toByte)

  private val blobTx: SignedTransaction =
    val chainId = blockchainConfig.chainId.value
    SignedTransaction.sign(
      BlobTransaction(
        chainId,
        0,
        BigInt(1),
        BigInt(1000),
        GasAmount(100000),
        Some(Address(ByteString(Array.fill(20)(0x16.toByte)))),
        0,
        ByteString.empty,
        Nil,
        BigInt(1),
        List(BlobVersionedHash(ByteString(Array(0x01.toByte) ++ Array.fill(31)(0.toByte))))
      ),
      crypto.generateKeyPair(new SecureRandom()),
      Some(chainId)
    )

  /** EIP-4844 network form: 0x03 || rlp([tx_payload_body, blobs, commitments, proofs]). */
  private val networkBytes: Map[ByteString, ByteString] =
    val body = rlp.encode(
      RLPList(
        RLPValue(Array.empty[Byte]),
        RLPList(RLPValue(zeroBlob)),
        RLPList(RLPValue(commitment)),
        RLPList(RLPValue(proof))
      )
    )
    Map(blobTx.hash.value -> ByteString(0x03.toByte +: body))

  "buildBlobsBundle" should {

    "carry the pool's sidecar and compute no cell proofs for a pre-Osaka payload" taggedAs UnitTest in {
      val bundle = service.buildBlobsBundle(Seq(blobTx), networkBytes, withCellProofs = false)
      bundle.blobs shouldBe Seq(ByteString(zeroBlob))
      bundle.commitments shouldBe Seq(ByteString(commitment))
      bundle.proofs shouldBe Seq(ByteString(proof))
      bundle.cellProofsPerBlob shouldBe empty
    }

    "compute CELLS_PER_EXT_BLOB cell proofs per blob when a BlobsBundleV2 reader needs them" taggedAs (
      SlowTest,
      CryptoTest
    ) in {
      val bundle = service.buildBlobsBundle(Seq(blobTx), networkBytes, withCellProofs = true)
      bundle.blobs shouldBe Seq(ByteString(zeroBlob))
      bundle.cellProofsPerBlob.map(_.size) shouldBe Seq(KzgCellProofs.CELLS_PER_EXT_BLOB)
      bundle.cellProofsPerBlob.flatten.foreach(_.size shouldBe KzgCellProofs.BYTES_PER_PROOF)
    }

    "always compute them for the testing_* namespace, which answers BlobsBundleV2 on every fork" taggedAs (
      SlowTest,
      CryptoTest
    ) in {
      service.blobsBundleFor(Seq(blobTx), networkBytes).cellProofsPerBlob.map(_.size) shouldBe
        Seq(KzgCellProofs.CELLS_PER_EXT_BLOB)
    }
  }
