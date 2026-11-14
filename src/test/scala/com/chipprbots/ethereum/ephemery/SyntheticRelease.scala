package com.chipprbots.ethereum.ephemery

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.ephemery.StrictJson.*

/** A valid release that follows a committed one by one reset interval (spec 028 R16): the timestamp and the reset are
  * shifted by one interval, the chain id and the iteration are one more, and the published hash is the one the library
  * computes. It tests the paths that need an iteration newer than the fixtures, without waiting for a real one.
  */
object SyntheticRelease:

  /** The inputs of the iteration after `base`. */
  def next(base: ReleaseInputs): ReleaseInputs =
    val genesis = parsed(base.genesisJson)
    val config = genesis.obj("config")
    val chainId = config.get("chainId") match
      case Some(n: JNum) => BigInt(n.raw)
      case other         => throw new AssertionError(s"chainId: $other")
    val timestamp = genesis.get("timestamp") match
      case Some(JStr(s)) => s.toLong
      case other         => throw new AssertionError(s"timestamp: $other")
    val iteration = (chainId - EphemeryRelease.IterationBase).toInt + 1
    val interval = EphemeryRelease.DefaultResetInterval
    val shifted = base
      .editGenesis(_.set("timestamp", JStr((timestamp + interval).toString)))
      .editConfig(_.set("chainId", num((chainId + 1).toLong)))
      .retention("GENESIS_TIMESTAMP", (timestamp + interval).toString)
      .retention("CHAIN_ID", (chainId + 1).toString)
      .retention("ITERATION_NUMBER", iteration.toString)
      .retention("ITERATION_RELEASE", s"ephemery-$iteration")
    // The published hash is what the library computes: the first run names it in the refusal.
    EphemeryRelease.validate(shifted) match
      case Left(ReleaseRefusal.HashMismatch(_, computed)) => shifted.copy(publishedHashText = computed)
      case other => throw new AssertionError(s"the synthetic release is not a hash mismatch first: $other")

  /** The entry the node's store holds for the release after `base`. */
  def nextEntry(base: ReleaseInputs, producedAt: java.time.Instant): ReleaseEntry =
    val valid = EphemeryRelease.validate(next(base)).fold(r => throw new AssertionError(r.message), identity)
    ReleaseEntry.of(valid, "https://example.invalid/synthetic", None, "test", producedAt)

  /** The same iteration with another genesis (a different gas limit) and the hash the library computes for it: a
    * release that was published again under the same name.
    */
  def variant(base: ReleaseInputs): ReleaseInputs =
    val changed = base.editGenesis(_.set("gasLimit", JStr("0x2255101")))
    EphemeryRelease.validate(changed) match
      case Left(ReleaseRefusal.HashMismatch(_, computed)) => changed.copy(publishedHashText = computed)
      case other => throw new AssertionError(s"the variant is not a hash mismatch first: $other")

  def variantEntry(base: ReleaseInputs, producedAt: java.time.Instant): ReleaseEntry =
    val valid = EphemeryRelease.validate(variant(base)).fold(r => throw new AssertionError(r.message), identity)
    ReleaseEntry.of(valid, "https://example.invalid/variant", None, "test", producedAt)

  /** The CL members a real release carries next to the EL ones, and the two that must never be staged. */
  val ClMembers: Seq[(String, Array[Byte])] = Seq(
    "config.yaml" -> "PRESET_BASE: mainnet\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
    "genesis.ssz" -> Array.tabulate[Byte](4096)(i => (i % 251).toByte),
    "deploy_block.txt" -> "0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
    "boot_enr.yaml" -> "- enr:-synthetic\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
  )
  val ExcludedMembers: Seq[(String, Array[Byte])] = Seq(
    "parsedBeaconState.json" -> "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
    "bootnode-keys/bootnode_pk.key.enc" -> "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)
  )

  /** The `testnet-all.tar.gz` of a release: a GNU tar, as the real ones are, with the release's EL members, the CL
    * members, the two the node never stages, and `extra` (a member to add, or to put in place of one).
    */
  def archive(inputs: ReleaseInputs, extra: Seq[(String, Array[Byte])] = Nil): Array[Byte] =
    import java.nio.charset.StandardCharsets.UTF_8
    def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)
    val el: Seq[(String, Array[Byte])] = Seq(
      Some(ReleaseInputs.GenesisFile -> bytes(inputs.genesisJson)),
      Some(ReleaseInputs.RetentionFile -> bytes(inputs.retentionVars)),
      Some(ReleaseInputs.PublishedHashFile -> bytes(inputs.publishedHashText)),
      inputs.chainspecJson.map(ReleaseInputs.ChainspecFile -> bytes(_)),
      inputs.enodesText.map(ReleaseInputs.EnodesFile -> bytes(_)),
      inputs.bootEnrText.map(ReleaseInputs.BootEnrFile -> bytes(_))
    ).flatten
    val replaced = extra.map(_._1).toSet
    val members = (el ++ ClMembers ++ ExcludedMembers).filterNot(m => replaced.contains(m._1)) ++ extra
    val parts = members.flatMap { case (name, data) =>
      val dirs = name.split('/').dropRight(1).scanLeft("")((acc, seg) => if acc.isEmpty then seg else s"$acc/$seg")
      dirs.filter(_.nonEmpty).map(d => TarFixture.dir(d + "/")).toSeq :+ TarFixture.file(name, data)
    }
    // a directory header may appear once
    val seen = scala.collection.mutable.Set.empty[Int]
    val unique = parts.filter(p => seen.add(java.util.Arrays.hashCode(p)))
    TarFixture.tgz(unique*)
