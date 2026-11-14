package com.chipprbots.ethereum.console.ops

import java.util.Locale

import scala.util.Try

/** What may be shown of a configuration value (spec 029 FR-058, research §7.4): no secret reaches a frame or a file.
  *
  * A key is hidden when it is on the list of keys known to hold a secret, or when its name has a secret's word in it,
  * so that a key added later is hidden before anyone thinks of it. A key that holds a PATH to a secret (a key file, a
  * keystore directory, a password file) is shown: the path says where the secret is and is not the secret, and a
  * file's contents are never read here. A URL is shown without its user-info.
  */
object Redaction:

  /** What a hidden value is drawn as. */
  val Hidden: String = "<hidden>"

  /** Keys that hold a secret itself, by their full path. */
  val SecretKeys: Set[String] = Set(
    "faucet.wallet-password"
  )

  /** A key whose last part has one of these words holds a secret, unless it is a path (see [[PathSuffixes]]). */
  val SecretWords: Seq[String] = Seq("password", "passphrase", "secret", "token", "private-key", "privatekey", "mnemonic", "seed")

  /** A key whose last part ends in one of these names a place, not a secret: it is shown. */
  val PathSuffixes: Seq[String] = Seq("-path", "-file", "-dir", "-directory", "path", "file", "dir")

  /** Keys that hold a URL which may carry credentials: shown without them. */
  val UrlKeys: Set[String] = Set("fukuii.sync.checkpoint-sync-url", "sync.checkpoint-sync-url")

  private def last(key: String): String = key.split('.').lastOption.getOrElse(key).toLowerCase(Locale.ROOT)

  /** Whether `key`'s value must not be shown. */
  def isSecret(key: String): Boolean =
    val name = last(key)
    val full = key.toLowerCase(Locale.ROOT)
    SecretKeys.exists(k => full == k || full.endsWith("." + k)) ||
    (SecretWords.exists(name.contains) && !PathSuffixes.exists(name.endsWith))

  /** `value` as it may be shown for `key`: hidden if the key holds a secret; without its user-info if it is a URL that
    * has any, whatever the key (a URL with credentials in it is a secret wherever it is written); else as it is.
    */
  def show(key: String, value: String): String =
    if isSecret(key) then Hidden else withoutUserInfo(value)

  /** A URL without the `user:password@` in it; anything else unchanged. */
  def withoutUserInfo(value: String): String =
    val trimmed = value.trim
    val at = trimmed.indexOf("://")
    if at <= 0 || !trimmed.take(at).forall(c => c.isLetterOrDigit || c == '+' || c == '-' || c == '.') then value
    else
      Try(new java.net.URI(trimmed)).toOption.filter(_.getRawUserInfo != null) match
        case Some(uri) =>
          val authority = uri.getRawAuthority
          val bare = authority.drop(authority.lastIndexOf('@') + 1)
          trimmed.replaceFirst(java.util.regex.Pattern.quote(authority), java.util.regex.Matcher.quoteReplacement(bare))
        case None =>
          // Not a URL the JDK parses: cut the user-info by hand, between the scheme and the first '@' before any '/'.
          val rest = trimmed.drop(at + 3)
          val slash = rest.indexOf('/')
          val host = if slash < 0 then rest else rest.take(slash)
          val mark = host.lastIndexOf('@')
          if mark < 0 then value else trimmed.take(at + 3) + host.drop(mark + 1) + (if slash < 0 then "" else rest.drop(slash))
