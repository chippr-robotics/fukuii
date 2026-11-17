package com.chipprbots.ethereum.ephemery

import java.time.Instant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.testing.Tags.*

/** The `release.json` codec: a round trip, and each rule of contracts/release-json.md as a refusal. */
class ReleaseMetadataSpec extends AnyFlatSpec with Matchers:

  private val good = ReleaseMetadata(
    release = "ephemery-165",
    iteration = 165,
    chainId = BigInt(39438165),
    genesisTimestamp = 1792695600L,
    resetInterval = 2419200L,
    resetTime = 1795114800L,
    genesisHash = Hash165,
    source = "https://github.com/ephemery-testnet/ephemery-genesis/releases/tag/ephemery-165",
    archiveSha256 = Some("ab" * 32),
    producedBy = "fukuii test",
    producedAt = Instant.parse("2026-11-12T10:00:00Z")
  )

  /** `good`'s text with the first occurrence of `from` replaced. */
  private def changed(from: String, to: String): String =
    val text = good.toJson
    withClue(s"$from not in the text")(text should include(from))
    text.replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to))

  private def refused(text: String): String = ReleaseMetadata.decode(text).left.getOrElse(fail("accepted"))

  "ReleaseMetadata" should "round-trip through its JSON" taggedAs (UnitTest) in {
    ReleaseMetadata.decode(good.toJson) shouldBe Right(good)
    ReleaseMetadata.decode(good.copy(archiveSha256 = None).toJson) shouldBe Right(good.copy(archiveSha256 = None))
  }

  it should "refuse an unknown schema" taggedAs (UnitTest) in {
    refused(changed("\"schema\": 1", "\"schema\": 2")) should include("schema 2")
  }

  it should "refuse a source that is not an https URL without credentials, query or fragment, or an absolute path" taggedAs (
    UnitTest
  ) in {
    refused(changed(good.source, "http://example.org/x")) should include("source")
    refused(changed(good.source, "https://u:p@example.org/x")) should include("source")
    refused(changed(good.source, "https://example.org/x?sig=abc")) should include("source")
    refused(changed(good.source, "relative/path")) should include("source")
    ReleaseMetadata.decode(changed(good.source, "/tmp/some/dir")).isRight shouldBe true
  }

  it should "refuse a release that disagrees with its iteration, and an iteration that is not the chain id minus 39438000" taggedAs (
    UnitTest
  ) in {
    refused(changed("\"release\": \"ephemery-165\"", "\"release\": \"ephemery-164\"")) should include(
      "not ephemery-165"
    )
    refused(changed("\"release\": \"ephemery-165\"", "\"release\": \"other\"")) should include("not ephemery-<N>")
    refused(changed("\"chainId\": \"39438165\"", "\"chainId\": \"39438166\"")) should include("minus")
  }

  it should "refuse a resetTime that is not the genesis timestamp plus the interval" taggedAs (UnitTest) in {
    refused(changed("\"resetTime\": 1795114800", "\"resetTime\": 1795114801")) should include("resetTime")
  }

  it should "refuse a bad hash, a bad archive hash, a bad instant and an over-long number" taggedAs (UnitTest) in {
    refused(changed(Hash165, "0x1234")) should include("genesisHash")
    refused(changed("ab" * 32, "xyz")) should include("archiveSha256")
    refused(changed("2026-11-12T10:00:00Z", "yesterday")) should include("producedAt")
    refused(changed("\"genesisTimestamp\": 1792695600", "\"genesisTimestamp\": " + "1" * 81)) should include(
      "longer than 80 characters"
    )
    refused("[]") should include("not a JSON object")
  }

  it should "bound the chainId digit string at 80 characters" taggedAs (UnitTest) in {
    refused(changed("\"chainId\": \"39438165\"", "\"chainId\": \"" + "1" * 81 + "\"")) should include(
      "not a decimal string"
    )
    // 80 digits are read, and then fail the iteration rule, not a parse
    refused(changed("\"chainId\": \"39438165\"", "\"chainId\": \"" + "1" * 80 + "\"")) should include("minus")
  }
