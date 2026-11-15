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

  /** A key with one of these words in a part of its name holds a secret, unless that part names a place (see
    * [[PathSuffixes]]). The name is read with its `-` and `_` taken out, so `private-key`, `private_key` and
    * `privateKey` are one word.
    */
  val SecretWords: Seq[String] =
    Seq(
      "password",
      "passwd",
      "pwd",
      "passphrase",
      "secret",
      "token",
      "privatekey",
      "apikey",
      "credential",
      "bearer",
      "mnemonic",
      "seed"
    )

  /** Keys with a secret's word in their name that hold a setting about the secret, not the secret: shown. */
  val ShownKeys: Set[String] = Set("keystore.minimal-passphrase-length", "keystore.allow-no-passphrase")

  /** A key whose last part ends in one of these names a place, not a secret: it is shown. */
  val PathSuffixes: Seq[String] = Seq("-path", "-file", "-dir", "-directory", "path", "file", "dir")

  /** Keys that hold a URL which may carry credentials: shown without them. */
  val UrlKeys: Set[String] = Set("fukuii.sync.checkpoint-sync-url", "sync.checkpoint-sync-url")

  private def holdsSecret(part: String): Boolean =
    val word = part.replace("-", "").replace("_", "")
    SecretWords.exists(word.contains) && !PathSuffixes.exists(part.endsWith)

  /** Whether `key`'s value must not be shown: its own name holds a secret's word, or the name of an object it is in
    * does, so that everything under `jwt-secret { … }` is hidden whole.
    */
  def isSecret(key: String): Boolean =
    val full = key.toLowerCase(Locale.ROOT)
    def named(keys: Set[String]): Boolean = keys.exists(k => full == k || full.endsWith("." + k))
    named(SecretKeys) || (!named(ShownKeys) && full.split('.').exists(holdsSecret))

  /** `value` as it may be shown for `key`: hidden if the key holds a secret; otherwise [[scrubbed]], whatever the key
    * (a URL with credentials in it is a secret wherever it is written).
    */
  def show(key: String, value: String): String =
    if isSecret(key) then Hidden else scrubbed(value)

  // A scheme, `://`, then everything up to the LAST `@` before the URL's path, a blank or a quote: a password may hold
  // an `@` or a comma. It stops where a comma is followed by another URL, so that each URL of a list is taken alone.
  // The scheme must start where no scheme character stands before it: without that, a long run of such characters
  // (a hex blob in a log line) is tried from every one of its positions.
  private val UserInfo =
    """(?i)(?<![a-z0-9+.\-])([a-z][a-z0-9+.\-]*)://((?:(?!,[a-z][a-z0-9+.\-]*://)[^/\s"'<>])*)@""".r

  private val NodeId = "[0-9a-fA-F]{128}".r

  // `?token=…`, `&api_key=…` in a URL's query: the value, up to the next `&`, blank or quote.
  private val QuerySecret =
    """(?i)([?&](?:access[_-]?token|token|api[_-]?key|key|secret|sig|signature|password|passwd|auth)=)[^&\s"'<>#]+""".r

  // `Bearer <credentials>` and `Basic <credentials>`, as an Authorization header is written.
  private val Authorization = """(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{8,}""".r

  /** `text` with the `user:password@` taken out of every URL in it, wherever in it the URL stands; anything else
    * unchanged. An enode's node id stands where a user would and is no credential: it stays.
    */
  def withoutUserInfo(text: String): String =
    if !text.contains("://") then text
    else
      UserInfo.replaceAllIn(
        text,
        m =>
          java.util.regex.Matcher.quoteReplacement(
            if m.group(1).equalsIgnoreCase("enode") && NodeId.pattern.matcher(m.group(2)).matches() then m.matched
            else m.group(1) + "://"
          )
      )

  /** Text that is no configuration value (a log line, a row of a view), or a value under a key that holds no secret,
    * as it may be written to a frame or a file: no URL keeps its credentials, no query string its token, and no
    * `Bearer` its credentials. What a secret looks like when nothing names it (64 hex digits) cannot be told from a
    * hash, and is left.
    */
  def scrubbed(text: String): String =
    val noUser = withoutUserInfo(text)
    val noQuery =
      if noUser.indexOf('=') < 0 then noUser
      else QuerySecret.replaceAllIn(noUser, m => java.util.regex.Matcher.quoteReplacement(m.group(1) + Hidden))
    Authorization.replaceAllIn(noQuery, m => java.util.regex.Matcher.quoteReplacement(m.group(1) + " " + Hidden))
