package com.chipprbots.ethereum.network.discovery

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode

/** The trust checks of EIP-1459 (DNS-based node lists), pure functions over the records a tree is made of. A tree is
  * trusted because its root carries a signature by a key the client already holds, and every other record is trusted
  * because its name is the hash of its content. This follows go-ethereum's `p2p/dnsdisc` (`tree.go` and `client.go`,
  * go-ethereum master a01129526); the line numbers below refer to it.
  *
  *   - the root's signature, over keccak256 of the root text without its signature, by the key in the tree's
  *     `enrtree://<key>@<domain>` URL: `tree.go:277-287`, `client.go:153-162`;
  *   - each branch, ENR and link entry hashes to the label it was fetched under, keccak256 of the entry text truncated
  *     and base32-encoded: `tree.go:267-271`, `client.go:207`;
  *   - each ENR's own signature (EIP-778, scheme `v4`): `tree.go:382-397` through `enode.New`.
  */
object Eip1459:

  val RootPrefix = "enrtree-root:v1"
  val LinkPrefix = "enrtree://"
  val BranchPrefix = "enrtree-branch:"
  val EnrPrefix = "enr:"

  /** A tree to walk and the key its root must be signed with: an `enrtree://<base32 compressed key>@<domain>` URL.
    * `tree.go:346-365` (`parseLink`).
    */
  final case class DnsTree(domain: String, publicKey: ByteString):
    def url: String = LinkPrefix + base32Encode(publicKey.toArray) + "@" + domain

  object DnsTree:
    def parse(url: String): Either[String, DnsTree] =
      if !url.startsWith(LinkPrefix) then Left(s"not an enrtree:// URL: $url")
      else
        val rest = url.drop(LinkPrefix.length)
        val at = rest.indexOf('@')
        if at < 0 then Left(s"no public key in $url, expected enrtree://<key>@<domain>")
        else
          val domain = rest.drop(at + 1)
          base32Decode(rest.take(at)) match
            case _ if domain.isEmpty => Left(s"no domain in $url")
            case Some(key) if key.length == CompressedKeyBytes && isPointOnCurve(key) =>
              Right(DnsTree(domain, ByteString(key)))
            case _ => Left(s"malformed public key in $url")

  /** A parsed root record. `signedText` is exactly what the signature covers. */
  final case class Root(eroot: String, lroot: String, seq: Long, signature: ByteString):
    def signedText: String = s"$RootPrefix e=$eroot l=$lroot seq=$seq"

  private val CompressedKeyBytes = 33
  private val RootSignatureBytes = 65
  private val MinHashBytes = 12 // tree.go:151
  private val MaxHashBytes = 32
  private val RootFormat = raw"enrtree-root:v1 e=(\S+) l=(\S+) seq=(\d+) sig=(\S+)".r

  /** `tree.go:322-336` (`parseRoot`): fixed field order, valid child hashes, a 65-byte base64url signature. */
  def parseRoot(record: String): Either[String, Root] =
    record match
      case RootFormat(eroot, lroot, seq, sig) =>
        if !isValidLabel(eroot) || !isValidLabel(lroot) then Left("invalid child hash")
        else
          (seq.toLongOption, base64UrlDecode(sig)) match
            case (None, _) => Left("invalid seq")
            case (Some(n), Some(bytes)) if bytes.length == RootSignatureBytes =>
              Right(Root(eroot, lroot, n, ByteString(bytes)))
            case _ => Left(s"invalid signature, want $RootSignatureBytes bytes of unpadded base64url")
      case _ => Left("not an enrtree-root:v1 record")

  /** `tree.go:283-287`. The recovery id is not used, like go-ethereum, and like `crypto.VerifySignature` a signature
    * with a high `s` is not accepted.
    */
  def verifyRoot(root: Root, publicKey: ByteString): Boolean =
    root.signature.length == RootSignatureBytes &&
      ecdsaVerify(
        publicKey.toArray,
        crypto.kec256(root.signedText.getBytes(UTF_8)),
        root.signature.take(RootSignatureBytes - 1).toArray
      )

  /** `tree.go:423-431` (`isValidHash`): base32 that decodes to 12 to 32 bytes. */
  def isValidLabel(label: String): Boolean =
    base32Decode(label).exists(bytes => bytes.length >= MinHashBytes && bytes.length <= MaxHashBytes)

  /** The label an entry is published under: its keccak256 hash, truncated to 16 bytes, in base32 (`tree.go:267-271`).
    */
  def entryLabel(text: String): String =
    base32Encode(crypto.kec256(text.getBytes(UTF_8)).take(16))

  /** `client.go:207`: the entry's keccak256 hash starts with the label's bytes. */
  def labelMatches(label: String, text: String): Boolean =
    base32Decode(label) match
      case Some(want) if want.length >= MinHashBytes && want.length <= MaxHashBytes =>
        java.util.Arrays.equals(crypto.kec256(text.getBytes(UTF_8)).take(want.length), want)
      case _ => false

  /** `tree.go:367-380` (`parseBranch`): every child must be a valid label; an empty branch is fine. */
  def parseBranch(record: String): Either[String, List[String]] =
    val body = record.stripPrefix(BranchPrefix)
    if body.isEmpty then Right(Nil)
    else
      val children = body.split(",", -1).toList
      if children.forall(isValidLabel) then Right(children) else Left("invalid child hash")

  /** Check an `enr:` entry's own signature: EIP-778 scheme `v4`, a signature of 64 bytes (r, s) over keccak256 of the
    * RLP of the record without its signature, by the record's `secp256k1` key. `tree.go:392` (`enode.New`).
    */
  def verifyEnr(record: String): Either[String, Unit] =
    try
      base64UrlDecode(record.stripPrefix(EnrPrefix)) match
        case None => Left("not unpadded base64url")
        case Some(bytes) =>
          rawDecode(bytes) match
            case list: RLPList if list.items.size >= 2 && list.items.size % 2 == 0 =>
              val signature = list.items.head match
                case RLPValue(sig) => sig
                case _             => Array.emptyByteArray
              val attributes = list.items
                .drop(2)
                .grouped(2)
                .collect { case Seq(RLPValue(k), RLPValue(v)) =>
                  new String(k, UTF_8) -> v
                }
                .toMap
              val scheme = attributes.get("id").map(new String(_, UTF_8))
              val key = attributes.getOrElse("secp256k1", Array.emptyByteArray)
              if scheme.contains("v4") then
                if signature.length != 64 then Left(s"signature is ${signature.length} bytes, want 64")
                else if key.length != CompressedKeyBytes then Left("no 33-byte secp256k1 key")
                else if ecdsaVerify(key, crypto.kec256(rlp.encode(RLPList(list.items.tail*))), signature) then Right(())
                else Left("signature does not verify against the record's key")
              else Left(s"identity scheme is ${scheme.getOrElse("missing")}, only v4 is valid")
            case _ => Left("not an RLP list of signature, seq and key/value pairs")
    catch case NonFatal(ex) => Left(s"cannot decode: ${ex.getMessage}")

  /** ECDSA over secp256k1 with a compressed key and `r || s`, rejecting `s` above half the curve order as go-ethereum's
    * `crypto.VerifySignature` does.
    */
  private[discovery] def ecdsaVerify(compressedKey: Array[Byte], hash: Array[Byte], rs: Array[Byte]): Boolean =
    try
      rs.length == 64 && compressedKey.length == CompressedKeyBytes && {
        val r = new java.math.BigInteger(1, rs.take(32))
        val s = new java.math.BigInteger(1, rs.drop(32))
        s.compareTo(crypto.curve.getN.shiftRight(1)) <= 0 && {
          val key = new ECPublicKeyParameters(crypto.curve.getCurve.decodePoint(compressedKey), crypto.curve)
          val signer = new ECDSASigner()
          signer.init(false, key)
          signer.verifySignature(hash, r, s)
        }
      }
    catch case NonFatal(_) => false

  private def isPointOnCurve(compressedKey: Array[Byte]): Boolean =
    try
      crypto.curve.getCurve.decodePoint(compressedKey)
      true
    catch case NonFatal(_) => false

  private val Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

  /** RFC 4648 base32 without padding, as Go's `base32.StdEncoding.WithPadding(NoPadding)` (`tree.go:256`). */
  private[discovery] def base32Encode(bytes: Array[Byte]): String =
    val out = new StringBuilder
    var buffer = 0
    var bits = 0
    bytes.foreach { b =>
      buffer = (buffer << 8) | (b & 0xff)
      bits += 8
      while bits >= 5 do
        bits -= 5
        out.append(Base32Alphabet.charAt((buffer >> bits) & 31))
      buffer &= (1 << bits) - 1
    }
    if bits > 0 then out.append(Base32Alphabet.charAt((buffer << (5 - bits)) & 31))
    out.toString

  /** The inverse. Upper case only, like Go; a length that no byte count produces (1, 3 or 6 mod 8) is rejected. */
  private[discovery] def base32Decode(text: String): Option[Array[Byte]] =
    if Set(1, 3, 6).contains(text.length % 8) then None
    else
      val values = text.map(Base32Alphabet.indexOf(_))
      if values.contains(-1) then None
      else
        val out = Array.newBuilder[Byte]
        var buffer = 0
        var bits = 0
        values.foreach { v =>
          buffer = (buffer << 5) | v
          bits += 5
          if bits >= 8 then
            bits -= 8
            out += ((buffer >> bits) & 0xff).toByte
            buffer &= (1 << bits) - 1
        }
        Some(out.result())

  /** Unpadded base64url (`tree.go:257`, `base64.RawURLEncoding`): padding or the standard alphabet is an error. */
  private def base64UrlDecode(text: String): Option[Array[Byte]] =
    if text.contains('=') then None
    else
      try Some(Base64.getUrlDecoder.decode(text))
      catch case _: IllegalArgumentException => None

/** The public keys the shipped chain configs' DNS trees are signed with, so a bare domain in `dns-discovery-domains` is
  * still an authenticated tree. go-ethereum ships its keys the same way, in code (`params/bootnodes.go:72`,
  * `dnsPrefix`, used by `KnownDNSNetwork`), not in a config file.
  *
  * Checked against the live trees on 2026-11-08: every root below verifies under the key beside it.
  */
object DnsTrustAnchors:
  import Eip1459.DnsTree

  /** The Ethereum Classic trees' key: core-geth `params/bootnodes_classic.go:28` (`dnsPrefixETC`), used there for
    * `all.classic.etcdisco.net` (line 30) and `all.mordor.blockd.info` (`bootnodes_mordor.go`).
    */
  val EtcKey = "AJE62Q4DUX4QMMXEHCSSCSC65TDHZYSMONSD64P3WULVLSF6MRQ3K"

  /** The Ethereum Foundation's key: go-ethereum `params/bootnodes.go:72`, used there for `all.sepolia.ethdisco.net`
    * (line 89). `KnownDNSNetwork` (`params/bootnodes.go:108-115`) uses this one key for every EF tree, so it also signs
    * `all.hoodi.ethdisco.net`: checked against the live Hoodi root on 2026-11-12 (`Eip1459Spec`, recorded vector).
    */
  val EthKey = "AKA3AM6LPBYEUDMVNU3BSVQJ5AD45Y7YPOHJLEF6W26QOE4VTUDPE"

  val shipped: Map[String, String] = Map(
    "all.classic.etcdisco.net" -> EtcKey,
    "all.classic.blockd.info" -> EtcKey,
    "all.mordor.etcdisco.net" -> EtcKey,
    "all.mordor.blockd.info" -> EtcKey,
    "all.sepolia.ethdisco.net" -> EthKey,
    "all.hoodi.ethdisco.net" -> EthKey
  )

  /** A `dns-discovery-domains` entry is either an `enrtree://<key>@<domain>` URL, which carries its own key, or a bare
    * domain, which must be one of [[shipped]]. Any other bare domain has no key to verify it with and is refused: an
    * unverified tree is not walked.
    */
  def resolve(entry: String): Either[String, DnsTree] =
    if entry.startsWith(Eip1459.LinkPrefix) then DnsTree.parse(entry)
    else
      shipped.get(entry.toLowerCase) match
        case Some(key) => DnsTree.parse(s"${Eip1459.LinkPrefix}$key@$entry")
        case None =>
          Left(
            s"no public key to verify '$entry' with; write it as ${Eip1459.LinkPrefix}<base32 public key>@$entry"
          )
