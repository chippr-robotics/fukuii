package com.chipprbots.ethereum.checkpointing

import akka.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.ECDSASignatureImplicits.ECDSASignatureOrdering

object CheckpointingTestHelpers {

  def createCheckpointSignatures(
      keys: Seq[AsymmetricCipherKeyPair],
      hash: ByteString
  ): Seq[ECDSASignature] =
    keys.map { k =>
      ECDSASignature.sign(hash.toArray, k)
    }.sorted
}
