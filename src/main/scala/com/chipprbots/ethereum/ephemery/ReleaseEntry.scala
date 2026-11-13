package com.chipprbots.ethereum.ephemery

import java.time.Instant

import com.chipprbots.ethereum.ephemery.EphemeryRelease.ValidRelease

/** One validated Ephemery release as the node keeps it: the rendered chain configuration, the rendered genesis and the
  * `release.json` that describes them (spec 028 data-model.md, "ReleaseEntry"). The same three files sit in the cache
  * under `releases/ephemery-N/` and in the jar under `ephemery/bundled/ephemery-N/`.
  *
  * An entry is trusted only after [[ReleaseEntry.verify]] has recomputed the genesis hash from its own two files.
  */
final case class ReleaseEntry(metadata: ReleaseMetadata, chainConf: String, genesisJson: String):
  def iteration: Int = metadata.iteration
  def release: String = metadata.release
  def genesisHash: String = metadata.genesisHash
  def genesisTime: Instant = Instant.ofEpochSecond(metadata.genesisTimestamp)
  def resetTime: Instant = Instant.ofEpochSecond(metadata.resetTime)

  /** `genesisTimestamp <= now < resetTime`. */
  def isCurrent(now: Instant): Boolean = !now.isBefore(genesisTime) && now.isBefore(resetTime)

object ReleaseEntry:

  val MetadataFile: String = "release.json"
  val ChainsDir: String = "chains"

  /** The entry of a release the library has just validated. */
  def of(
      release: ValidRelease,
      source: String,
      archiveSha256: Option[String],
      producedBy: String,
      producedAt: Instant
  ): ReleaseEntry =
    ReleaseEntry(
      ReleaseMetadata.of(release, source, archiveSha256, producedBy, producedAt),
      release.chainConf,
      release.encodedGenesis
    )

  /** Reads `release.json` and re-validates the two files against it: the chain configuration must load, state the
    * metadata's chain id, and, with the genesis, hash to the metadata's genesis hash, which must also be the one the
    * configuration states. `Left` names what is wrong, for the log.
    */
  def verify(metadataText: String, chainConf: String, genesisJson: String): Either[String, ReleaseEntry] =
    for
      metadata <- ReleaseMetadata.decode(metadataText)
      recomputed <- EphemeryRelease.recompute(chainConf, genesisJson).left.map(_.message)
      _ <- Either.cond(
        recomputed.chainId == metadata.chainId,
        (),
        s"the chain configuration says chain id ${recomputed.chainId}, release.json ${metadata.chainId}"
      )
      _ <- Either.cond(
        recomputed.genesisTimestamp == metadata.genesisTimestamp,
        (),
        s"the genesis says timestamp ${recomputed.genesisTimestamp}, release.json ${metadata.genesisTimestamp}"
      )
      _ <- Either.cond(
        recomputed.hashHex == metadata.genesisHash,
        (),
        s"the files hash to genesis ${recomputed.hashHex}, release.json says ${metadata.genesisHash}"
      )
      _ <- Either.cond(
        recomputed.statedHash.contains(metadata.genesisHash),
        (),
        s"the chain configuration states genesis-hash ${recomputed.statedHash.getOrElse("(none)")}, release.json says ${metadata.genesisHash}"
      )
    yield ReleaseEntry(metadata, chainConf, genesisJson)
