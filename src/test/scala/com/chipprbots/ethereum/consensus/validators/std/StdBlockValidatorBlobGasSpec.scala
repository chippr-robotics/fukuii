package com.chipprbots.ethereum.consensus.validators.std

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*

/** EIP-4844 body rule: a header's `blobGasUsed` must equal (number of blob versioned hashes carried by the block's
  * transactions) x GAS_PER_BLOB (131,072). go-ethereum enforces it in `core/block_validator.go` `ValidateBody` ("blob
  * gas used mismatch"), and also rejects blob-carrying bodies under a header with no `blobGasUsed` ("data blobs present
  * in block body").
  *
  * WHY THIS SPEC EXISTS. On 33b730e75 hive consume-rlp failed 90 of the 100 Prague cases of execution-spec-tests v5.4.0
  * `tests/cancun/eip4844_blobs/test_excess_blob_gas.py::test_invalid_blob_gas_used_in_header` with "blockHash mismatch
  * in last block": fukuii IMPORTED the invalid block. The rule lived only in `EngineApiService.newPayload`, so the
  * chain.rlp import path (`ChainImporter` -> `StdValidators.validateBlockBeforeExecution` ->
  * `StdBlockValidator.validateHeaderAndBody`) never ran it. The header validator's own blob checks (at most the
  * per-block max, a multiple of GAS_PER_BLOB, excessBlobGas against the parent) cannot catch it: they never see the
  * body. The 10 cases that did pass were exactly those whose header value the header validator rejects on its own.
  *
  * The blocks below are the fixtures' own RLP, verbatim. Each decode is pinned to the fixture's block hash so a decoder
  * change cannot silently turn these into tests of some other block.
  */
class StdBlockValidatorBlobGasSpec extends AnyFlatSpec with Matchers:

  private def decode(hex: String): Block = Hex.decode(hex).toBlock

  private def blobCount(block: Block): Int =
    block.body.transactionList.map { stx =>
      stx.tx match
        case bt: BlobTransaction => bt.blobVersionedHashes.size
        case _                   => 0
    }.sum

  /** test_invalid_blob_gas_used_in_header[fork_Prague-new_blobs_1-header_blob_gas_used_0-blockchain_test-
    * parent_blobs_0]: one blob, header says 0.
    */
  private val oneBlobHeaderZero: String =
    "f902edf9025ca05725e97ecf2cfc09c0dfd66b525a6738f3a8ecf9eea62ba4db38fe0ac3e869f3a01dcc4de8dec75d7aab85" +
      "b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa012841be23e0e" +
      "fd578ed2d0f5da242af1746bfa5cd375807d9afe65b2ae718742a083260093ca511f49d8d5bfd6ef9a7948f520b5dbb2eae2" +
      "9e0a5756bbbd1e27eaa0c117ad0158b04d4277c8a0d1b440360bf3f011ad7caccf8740df472c96e8f5ccb901000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0080018407270e0082a8610c80a0000000000000000000000000000000000000000000000000000000000000000088000000" +
      "000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218083040000a00000000000" +
      "000000000000000000000000000000000000000000000000000000a0e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b" +
      "934ca495991b7852b855f88ab88803f8850180800782afc894495ae7a6d3dbae90ce7440902e7c485c456a36480180c001e1" +
      "a0010000000000000000000000000000000000000000000000000000000000000080a01756c37e755e7dbb3906c215c96aab" +
      "67ec06a7594abee0837eeaff6071215cfda078ade425b420d3176a36f2e5ed6b452435ad79d791323133ad78a7bf6651b686" +
      "c0c0"

  /** ...[fork_Prague-new_blobs_0-header_blob_gas_used_131072-...]: no blob transaction at all, header says one blob. */
  private val zeroBlobsHeaderOne: String =
    "f902cdf9025fa0eab115cc7efce1808ce6853a24667bd5e3c466ef412da657bf6832b165794b85a01dcc4de8dec75d7aab85" +
      "b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa06eb68ebac04a" +
      "bb0b4824c5e804f89a8e34eecc63a03936f28d3f3e9497e0ea50a0c6c191802aaaee7c9b13279cdf5ed1ad06a9f21ed94bee" +
      "e1e04b02800f25fcaba0167497e0db677e533dde7e46c3f485c45662ceb46281d7073354e80b5a40f454b901000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0080018407270e0082a8610c80a0000000000000000000000000000000000000000000000000000000000000000088000000" +
      "000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218302000083040000a00000" +
      "000000000000000000000000000000000000000000000000000000000000a0e3b0c44298fc1c149afbf4c8996fb92427ae41" +
      "e4649b934ca495991b7852b855f867b86502f8620180800782afc894da041a75ee601f744012b4dc3130d97b2e3895d30180" +
      "c001a02a19d66b6c0ed4d3131da594149a5e0df200699c6643825dba53bfd35ccd3373a0427aea66fe27d16d201368d6e24e" +
      "1a8c2af2ee3869e208bf68c290de14efb3e1c0c0"

  /** ...[fork_Prague-new_blobs_2-header_blob_gas_used_131072-...]: two blobs, header says one. */
  private val twoBlobsHeaderOne: String =
    "f90312f9025fa069c54d90eb77d8963f6afb4de87806b21e28f80cbc1bed39a95d8e44fa1567eca01dcc4de8dec75d7aab85" +
      "b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa054773fb696eb" +
      "7bb998c413fb44ba482bf813f216105d4cf9cff2bc68bcc484f5a0bfc79c7067ad4e37d6550e9c054d70b1f697a6f17e24d9" +
      "23aa6e77570ef93654a0c117ad0158b04d4277c8a0d1b440360bf3f011ad7caccf8740df472c96e8f5ccb901000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0080018407270e0082a8610c80a0000000000000000000000000000000000000000000000000000000000000000088000000" +
      "000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218302000083040000a00000" +
      "000000000000000000000000000000000000000000000000000000000000a0e3b0c44298fc1c149afbf4c8996fb92427ae41" +
      "e4649b934ca495991b7852b855f8acb8aa03f8a70180800782afc894b1154e89cfbde6880ae2fcb3412944d4638314620180" +
      "c001f842a00100000000000000000000000000000000000000000000000000000000000000a0010000000000000000000000" +
      "000000000000000000000000000000000000000101a03142f58d4d84623bed17ad7e7d3785f3ba4658cfe7dd38c863326bac" +
      "811b1ba2a01b17a370bfa7afd132010b0018bca47bfe7e1c1fa82f2aa1894e1f0da4d96af3c0c0"

  /** test_correct_excess_blob_gas_calculation[fork_Prague-parent_excess_blobs_0-parent_blobs_0-blockchain_test-
    * new_blobs_1]: a VALID block, one blob, header says 131,072.
    */
  private val validOneBlob: String =
    "f902edf9025ca0b655798cc28a4992fdc10899e7435b495d4e29adc41651e1daed5514d713622da01dcc4de8dec75d7aab85" +
      "b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa007b7cea6cacc" +
      "81e22d42383e783bec03f34255e2e11606c7ee2c9a7538862368a08d887e64febb153235a8733ee294e3efdb2859b9ba5ce5" +
      "e7d20cf534d0d608d7a0c117ad0158b04d4277c8a0d1b440360bf3f011ad7caccf8740df472c96e8f5ccb901000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0080018407270e0082a8610c80a0000000000000000000000000000000000000000000000000000000000000000088000000" +
      "000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218302000080a00000000000" +
      "000000000000000000000000000000000000000000000000000000a0e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b" +
      "934ca495991b7852b855f88ab88803f8850180800782afc89457f8e2816335791d1f9ebf111f51123b169dac350180c001e1" +
      "a0010000000000000000000000000000000000000000000000000000000000000080a0b85d301f110d9ffb3ab4a6c0190689" +
      "1c48b014a949d85ddcbdb752353c547193a034679f04452d8d974bb8637d4c5c5f4b003af4290fc50dd2adfe6aaf38e54538" +
      "c0c0"

  "the fixture blocks" should "decode to exactly the fixtures' block hashes and blob counts" taggedAs (
    ConsensusTest,
    UnitTest
  ) in {
    val cases = Seq(
      (oneBlobHeaderZero, "0xe51a47244e85309cdfd3356176e6a2f0c6efdc53a118674fd773dc68cf6fb370", 1, BigInt(0)),
      (zeroBlobsHeaderOne, "0x66e6f5e8a9adff55826e3fdd90e7626e35e64583d6c06b8735d86fe9a08f64ea", 0, BigInt(131072)),
      (twoBlobsHeaderOne, "0xc8ccdfc27b6a88708036ed67b3259b68110eedf9081eff0fb597a091ffe4d357", 2, BigInt(131072)),
      (validOneBlob, "0x03982f09d2119b48659211fe784d8faf8a5361ef9c9495790f8e8a2dc0da9b41", 1, BigInt(131072))
    )
    cases.foreach { case (rlp, hash, blobs, headerBlobGas) =>
      val block = decode(rlp)
      block.header.hashAsHexString shouldBe hash.stripPrefix("0x")
      block.header.extraFields shouldBe a[HefPostPrague]
      blobCount(block) shouldBe blobs
      block.header.blobGasUsed shouldBe Some(headerBlobGas)
    }
  }

  "validateHeaderAndBody" should "reject a header claiming 0 blob gas over a one-blob body" taggedAs (
    ConsensusTest,
    UnitTest
  ) in {
    val block = decode(oneBlobHeaderZero)
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe
      Left(BlockBlobGasUsedError(headerBlobGasUsed = BigInt(0), computedBlobGasUsed = BigInt(131072)))
  }

  it should "reject a header claiming one blob over a body with no blob transaction" taggedAs (
    ConsensusTest,
    UnitTest
  ) in {
    val block = decode(zeroBlobsHeaderOne)
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe
      Left(BlockBlobGasUsedError(headerBlobGasUsed = BigInt(131072), computedBlobGasUsed = BigInt(0)))
  }

  it should "reject a header claiming one blob over a two-blob body" taggedAs (ConsensusTest, UnitTest) in {
    val block = decode(twoBlobsHeaderOne)
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe
      Left(BlockBlobGasUsedError(headerBlobGasUsed = BigInt(131072), computedBlobGasUsed = BigInt(262144)))
  }

  it should "accept the valid one-blob block whose header says 131,072" taggedAs (ConsensusTest, UnitTest) in {
    val block = decode(validOneBlob)
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe Right(BlockValid)
  }

  it should "reject blob transactions under a header that has no blobGasUsed field" taggedAs (
    ConsensusTest,
    UnitTest
  ) in {
    // go-ethereum: "data blobs present in block body". A Shanghai-shaped header over the valid block's one-blob body;
    // the withdrawals root is carried over so the blob is the only thing wrong with the block.
    val block = decode(validOneBlob)
    val preCancun = block.header.copy(
      extraFields = HefPostShanghai(block.header.baseFee.get, block.header.withdrawalsRoot.get)
    )
    StdBlockValidator.validateHeaderAndBody(preCancun, block.body) shouldBe Left(BlockBlobsWithoutBlobGasError(1))
  }

  it should "leave blocks with neither blobGasUsed nor blob transactions untouched (ETC, pre-Cancun ETH)" taggedAs (
    ConsensusTest,
    OlympiaTest,
    UnitTest
  ) in {
    val block = Fixtures.Blocks.ValidBlock.block
    block.header.blobGasUsed shouldBe None
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe Right(BlockValid)
  }

  it should "accept a Cancun-shaped header declaring 0 blob gas over a body with no transactions" taggedAs (
    ConsensusTest,
    UnitTest
  ) in {
    // Strip the valid block's only transaction and point the header at the empty txs trie: 0 blobs, 0 blob gas.
    val block = decode(validOneBlob)
    val emptyBody = block.body.copy(transactionList = Seq.empty)
    val header = block.header.copy(
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      extraFields = block.header.extraFields match
        case p: HefPostPrague => p.copy(blobGasUsed = BigInt(0))
        case other            => fail(s"unexpected extra fields $other")
    )
    StdBlockValidator.validateHeaderAndBody(header, emptyBody) shouldBe Right(BlockValid)
  }
