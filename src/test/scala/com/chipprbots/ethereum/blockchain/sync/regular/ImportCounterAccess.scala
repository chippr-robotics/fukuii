package com.chipprbots.ethereum.blockchain.sync.regular

/** Test-only door into `BlockImporter`'s exhaust counter, for specs outside this package. */
object ImportCounterAccess:
  def survivedExhausts: Int = BlockImporter.survivedExhausts
