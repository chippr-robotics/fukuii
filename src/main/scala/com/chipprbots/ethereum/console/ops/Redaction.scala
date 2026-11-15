package com.chipprbots.ethereum.console.ops

import java.util.Locale

/** What may be shown of a configuration value (spec 029 FR-058, research §7.4): no secret reaches a frame or a file.
  *
  * A key is hidden when it is on the list of keys known to hold a secret, or when its name has a secret's word in it,
  * so that a key added later is hidden before anyone thinks of it. A key that holds a PATH to a secret (a key file, a
  * keystore directory, a password file) is shown: the path says where the secret is and is not the secret, and a file's
  * contents are never read here. A URL is shown without its user-info.
  */
object Redaction:

  /** What a hidden value is drawn as. */
  val Hidden: String = "<hidden>"

  /** Keys that hold a secret itself, by their full path. */
  val SecretKeys: Set[String] = Set(
    "faucet.wallet-password"
  )

  /** A key whose last part has one of these words holds a secret, unless it is a path (see [[PathSuffixes]]). */
  val SecretWords: Seq[String] =
    Seq("password", "passphrase", "secret", "token", "private-key", "privatekey", "mnemonic", "seed")

  /** A key whose last part ends in one of these names a place, not a secret: it is shown. */
  val PathSuffixes: Seq[String] = Seq("-path", "-file", "-dir", "-directory", "path", "file", "dir")

  /** Keys that hold a URL which may carry credentials: shown without them. */
  val UrlKeys: Set[String] = Set("fukuii.sync.checkpoint-sync-url", "sync.checkpoint-sync-url")

  private def holdsSecret(part: String): Boolean =
    SecretWords.exists(part.contains) && !PathSuffixes.exists(part.endsWith)

  /** Whether `key`'s value must not be shown: its own name holds a secret's word, or the name of an object it is in
    * does, so that everything under `jwt-secret { … }` is hidden whole.
    */
  def isSecret(key: String): Boolean =
    val full = key.toLowerCase(Locale.ROOT)
    SecretKeys.exists(k => full == k || full.endsWith("." + k)) || full.split('.').exists(holdsSecret)

  /** `value` as it may be shown for `key`: hidden if the key holds a secret; without the user-info of any URL in it,
    * whatever the key (a URL with credentials in it is a secret wherever it is written); else as it is.
    */
  def show(key: String, value: String): String =
    if isSecret(key) then Hidden else withoutUserInfo(value)

  // A scheme, `://`, then everything up to the LAST `@` before the URL's path, a blank or a quote: a password may hold
  // an `@` or a comma. It stops where a comma is followed by another URL, so that each URL of a list is taken alone.
  private val UserInfo = """(?i)([a-z][a-z0-9+.\-]*)://((?:(?!,[a-z][a-z0-9+.\-]*://)[^/\s"'<>])*)@""".r

  private val NodeId = "[0-9a-fA-F]{128}".r

  /** `text` with the `user:password@` taken out of every URL in it, wherever in it the URL stands; anything else
    * unchanged. An enode's node id stands where a user would and is no credential: it stays.
    */
  def withoutUserInfo(text: String): String =
    UserInfo.replaceAllIn(
      text,
      m =>
        java.util.regex.Matcher.quoteReplacement(
          if m.group(1).equalsIgnoreCase("enode") && NodeId.pattern.matcher(m.group(2)).matches() then m.matched
          else m.group(1) + "://"
        )
    )
