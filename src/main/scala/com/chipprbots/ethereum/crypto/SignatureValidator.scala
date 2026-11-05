package com.chipprbots.ethereum.crypto

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.ByteStringUtils

// scalastyle:off regex
@annotation.nowarn("cat=deprecation")
// DEFERRED 2026-11-05 (spec 020 DC-4, see specs/020-scala-3-9-lts-migration/dc4-ledger.md): scala.App is deprecated
// since 3.8.0. `def main` changes initialisation order (delayedInit), so forge decides in a follow-up.
object SignatureValidator extends App with SecureRandomBuilder with JsonMethodsImplicits:

  args match
    case Array(pk, sig, msgHash) =>
      Try {
        val signature = ECDSASignature.fromBytes(ByteStringUtils.string2hash(sig))
        val msg = ByteStringUtils.string2hash(msgHash)

        signature.flatMap(_.publicKey(msg))
      } match
        case Failure(exception) =>
          System.err.println(
            s"Can't recover public key from signature [$sig] and msg [$msgHash]: ERROR: ${exception.getMessage}"
          )
          sys.exit(1)
        case Success(recoveredPk) =>
          val publicKey = ByteStringUtils.string2hash(pk)
          recoveredPk match
            case Some(recoveredKey) =>
              if recoveredKey == publicKey then
                System.err.println(
                  s"Recovered public key [${ByteStringUtils.hash2string(recoveredKey)}] is the same as given one"
                )
              else
                System.err.println(s"Recovered public key [${ByteStringUtils
                    .hash2string(recoveredKey)}] is different than given [${ByteStringUtils.hash2string(publicKey)}]")
                sys.exit(1)
            case None =>
              System.err.println(s"Can't recover public key from signature [$sig] and msg [$msgHash]")
              sys.exit(1)
    case _ =>
      val keyPair = crypto.generateKeyPair(secureRandom)
      val pkStr = ByteStringUtils.hash2string(ByteString(crypto.pubKeyFromKeyPair(keyPair)))
      val hash = kec256(Array(1.toByte))
      val hashStr = ByteStringUtils.hash2string(ByteString(hash))
      val signature = ECDSASignature.sign(hash, keyPair)
      val sigStr = ByteStringUtils.hash2string(signature.toBytes)
      System.err.println(
        s"Bad Input. Example usage: [signature-validator publicKey signature message_hash]. Example: [signature-validator $pkStr $sigStr $hashStr]"
      )
      sys.exit(1)
