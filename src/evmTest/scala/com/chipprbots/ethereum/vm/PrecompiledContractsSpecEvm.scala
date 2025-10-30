package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.SignedTransaction.{FirstByteOfAddress, LastByteOfAddress}
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.vm.utils.EvmTestEnv
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PrecompiledContractsSpecEvm extends AnyFunSuite with Matchers with SecureRandomBuilder {

  test("Precompiled Contracts") {
    val keyPair = generateKeyPair(secureRandom)
    val bytes: Array[Byte] = crypto.kec256(ByteString("aabbccdd").toArray[Byte])
    val signature = ECDSASignature.sign(bytes, keyPair)
    val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false)
    val address = crypto.kec256(pubKey.tail).slice(FirstByteOfAddress, LastByteOfAddress)
    val expectedOutput = ripemd160(sha256(address))

    new EvmTestEnv {
      val (_, contract) = deployContract("PrecompiledContracts")
      val result = contract.usePrecompiledContracts(bytes, signature.v, signature.r, signature.s).call()

      // even though contract specifies bytes20 as the return type, 32-byte (right zero padded) value is returned
      result.returnData.take(20) shouldEqual expectedOutput
    }
  }
}
