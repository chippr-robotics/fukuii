package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import com.google.common.hash.BloomFilter

/** The coordinator state `ContractWorkCarry` reads and writes (spec 016 C-A4, research.md R14b: 4, about 15 with the
  * task-file I/O values). The contract task files, their streams and counts, the codeHash dedup filter and the carried
  * prefix counts are concrete `val`s whose initializers open files or copy the previous coordinator's prefix, or `var`s
  * whose initializer is not a literal, so rule (d) keeps them in the core (coordinator-characterization.md §3.2); the
  * core's `onStart`, `onStop`, `sendProgressSnapshot` and the trie assembly's `finalizing` read them too.
  */
private[actors] trait ContractWorkCarryState:
  def carriedStorageCount: Long
  def carriedCodeHashesCount: Long
  def contractAccountsOut: BufferedOutputStream
  def contractStorageFile: Path
  def contractStorageFos: FileOutputStream
  def contractStorageOut: BufferedOutputStream
  def uniqueCodeHashesFile: Path
  def uniqueCodeHashesFos: FileOutputStream
  def uniqueCodeHashesOut: BufferedOutputStream
  def codeHashBloom: BloomFilter[ByteString]
  var contractAccountsCount: Long
  var contractStorageCount: Long
  var uniqueCodeHashesCount: Long
