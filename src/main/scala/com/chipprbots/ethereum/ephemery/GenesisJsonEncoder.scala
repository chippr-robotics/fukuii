package com.chipprbots.ethereum.ephemery

import java.util.regex.Pattern

import com.chipprbots.ethereum.ephemery.StrictJson.*

/** Re-encodes a release's `genesis.json` into the file `GenesisDataLoader` reads (rules R9, R10, R11 and the alloc
  * rules of spec 028), and refuses what the loader would silently misread.
  *
  * The loader reads `timestamp`, `gasLimit`, `difficulty`, `baseFeePerGas`, `blobGasUsed` and `excessBlobGas` as hex
  * whether or not they carry `0x`, while go-ethereum's `genesis.json` writes them in decimal (Ephemery writes the
  * timestamp as the decimal string "1790276400"). Loaded verbatim that is a different timestamp and a genesis hash no
  * peer accepts, with no error. So every such field is converted to `0x`-hex here, and the other header fields are
  * checked for shape and width before they reach the loader's width-blind hex decoder.
  *
  * Pure: no `Config`, no clock, no file.
  */
object GenesisJsonEncoder:

  /** The ceiling of every header quantity. The loader reads the timestamp with `.toLong`, which wraps silently above
    * 2^63-1, and go-ethereum holds these fields in 64 bits: so no quantity above it is read.
    */
  val MaxQuantity: BigInt = (BigInt(1) << 63) - 1

  private val MaxUInt256: BigInt = (BigInt(1) << 256) - 1

  /** The top-level keys the loader reads, plus the three it derives (and so insists are zero). */
  private val KnownTopKeys: Set[String] = Set(
    "config",
    "alloc",
    "nonce",
    "mixHash",
    "mixhash",
    "difficulty",
    "extraData",
    "gasLimit",
    "coinbase",
    "timestamp",
    "baseFeePerGas",
    "blobGasUsed",
    "excessBlobGas",
    "parentHash",
    "number",
    "gasUsed"
  )

  private val DerivedZeroKeys: List[String] = List("parentHash", "number", "gasUsed")
  private val AccountKeys: Set[String] = Set("balance", "nonce", "code", "storage")

  private val HexQuantity = Pattern.compile("0[xX][0-9a-fA-F]{1,80}")
  private val DecimalQuantity = Pattern.compile("[0-9]{1,18}")
  private val NonceText = Pattern.compile("0x[0-9a-fA-F]{1,16}")
  private val MixHashText = Pattern.compile("0x[0-9a-fA-F]{64}")
  private val CoinbaseText = Pattern.compile("0x[0-9a-fA-F]{40}")
  private val ExtraDataText = Pattern.compile("0x([0-9a-fA-F]{2}){0,32}")
  private val CodeText = Pattern.compile("0[xX]([0-9a-fA-F]{2})*")
  private val AllocKeyDigits = Pattern.compile("[0-9a-fA-F]{1,40}")
  private val UInt256Dec = Pattern.compile("[0-9]{1,80}")
  private val UInt256Hex = Pattern.compile("0[xX][0-9a-fA-F]{1,80}")
  private val ZeroText = Pattern.compile("0*")

  private val ZeroMixHash = "0x" + "0" * 64
  private val ZeroCoinbase = "0x" + "0" * 40

  /** The re-encoded genesis and what the caller needs from the pass.
    *
    * @param json
    *   the file to write as `ephemery-genesis.json`
    * @param timestamp
    *   the genesis timestamp
    * @param timestampText
    *   the timestamp as the release wrote it (for messages)
    * @param accounts
    *   the normalised alloc addresses (lowercase, 40 digits, no prefix)
    */
  final case class Encoded(json: String, timestamp: Long, timestampText: String, accounts: Set[String])

  private def cannot(feature: String, detail: String): Left[ReleaseRefusal, Nothing] =
    Left(ReleaseRefusal.CannotRepresent(feature, detail))

  private def inconsistent(detail: String): Left[ReleaseRefusal, Nothing] =
    Left(ReleaseRefusal.Inconsistent(detail))

  /** A header quantity: `0x`-hex (either case of the prefix), or a decimal below 10^18 (a string or a JSON integer),
    * never a float or an exponent, and never above [[MaxQuantity]]. `field` names the field in the message.
    */
  def quantity(field: String, value: Option[Js]): Either[ReleaseRefusal, BigInt] =
    val text = value.map(show).getOrElse("null")
    parseQuantity(value) match
      case None => cannot("a header quantity", s"genesis.$field is neither 0x-hex nor a decimal below 10^18: '$text'")
      case Some(n) if n > MaxQuantity =>
        cannot("a header quantity", s"genesis.$field is above 2^63-1, the largest value fukuii reads: '$text'")
      case Some(n) => Right(n)

  /** The one parser of a quantity written as a JSON string or integer: `0x`-hex (either case of the prefix) or a
    * decimal below 10^18, never a float, an exponent or a sign. It does not apply the [[MaxQuantity]] ceiling. Shared
    * by the header quantities and the fork timestamps, so the two cannot drift.
    */
  def parseQuantity(value: Option[Js]): Option[BigInt] = value match
    case Some(js @ (_: JStr | _: JNum)) =>
      val text = show(js)
      if HexQuantity.matcher(text).matches() then Some(BigInt(text.substring(2), 16))
      else if DecimalQuantity.matcher(text).matches() then Some(BigInt(text))
      else None
    case _ => None

  /** `0x` plus lowercase digits without leading zeros; zero is `0x0`, never a bare `0x`. */
  def hex(n: BigInt): String = "0x" + n.toString(16)

  /** A uint256 as the loader reads one (hex with `0x`, or decimal), and strictly: digits only, below 2^256. */
  private val PrefixedHex = Pattern.compile("0[xX][0-9a-fA-F]+")

  /** A storage key or value: go-ethereum reads both as hex, so one without the `0x` prefix is refused rather than read
    * as decimal (the loader would, and the genesis hash would come out wrong).
    */
  private def storageWord(what: String, value: Js): Either[ReleaseRefusal, BigInt] = value match
    case JStr(s) if PrefixedHex.matcher(s).matches() => uint256(what, value)
    case other => cannot("an alloc value", s"$what must be 0x-prefixed hex, as go-ethereum reads it: '${show(other)}'")

  private def uint256(what: String, value: Js): Either[ReleaseRefusal, BigInt] = value match
    case JStr(s) if UInt256Hex.matcher(s).matches() => bounded(what, s, BigInt(s.substring(2), 16))
    case JStr(s) if UInt256Dec.matcher(s).matches() => bounded(what, s, BigInt(s))
    case other => cannot("an alloc value", s"$what is not a 0x-hex or decimal number: '${show(other)}'")

  private def bounded(what: String, text: String, n: BigInt): Either[ReleaseRefusal, BigInt] =
    if n > MaxUInt256 then cannot("an alloc value", s"$what is 2^256 or more: '$text'") else Right(n)

  /** The address an alloc key names, as the loader pads it: lowercase, 40 digits, no prefix. */
  private[ephemery] def normaliseAddress(key: String): String =
    val bare = if key.startsWith("0x") || key.startsWith("0X") then key.substring(2) else key
    bare.toLowerCase.reverse.padTo(40, '0').reverse

  /** Re-encodes `genesis` (the parsed `genesis.json` object). The release's `config` is checked elsewhere. */
  def encode(genesis: JObj): Either[ReleaseRefusal, Encoded] =
    for
      _ <- topLevelKeys(genesis)
      _ <- derivedZero(genesis)
      mix <- mixHash(genesis)
      timestampJs <- genesis.get("timestamp") match
        case None | Some(JNull) => cannot("a header quantity", "genesis.timestamp missing")
        case Some(js)           => Right(js)
      timestamp <- quantity("timestamp", Some(timestampJs))
      gasLimit <- quantity("gasLimit", genesis.get("gasLimit"))
      difficulty <- quantity("difficulty", genesis.get("difficulty"))
      baseFee <- optionalQuantity(genesis, "baseFeePerGas")
      blobGasUsed <- optionalQuantity(genesis, "blobGasUsed")
      excessBlobGas <- optionalQuantity(genesis, "excessBlobGas")
      nonce <- text(genesis, "nonce", "0x0", NonceText, "0x followed by 1 to 16 hex digits")
      extraData <- extraData(genesis)
      coinbase <- text(genesis, "coinbase", ZeroCoinbase, CoinbaseText, "0x followed by 40 hex digits (20 bytes)")
      alloc <- allocOf(genesis)
      checked <- checkAlloc(alloc)
    yield
      val header: Vector[(String, Js)] =
        Vector(
          "nonce" -> JStr(nonce),
          "mixHash" -> JStr(mix),
          "difficulty" -> JStr(hex(difficulty)),
          "extraData" -> JStr(extraData),
          "gasLimit" -> JStr(hex(gasLimit)),
          "coinbase" -> JStr(coinbase),
          "timestamp" -> JStr(hex(timestamp))
        ) ++
          baseFee.map(v => "baseFeePerGas" -> JStr(hex(v))) ++
          blobGasUsed.map(v => "blobGasUsed" -> JStr(hex(v))) ++
          excessBlobGas.map(v => "excessBlobGas" -> JStr(hex(v)))
      Encoded(
        json = render(JObj(header :+ ("alloc" -> JObj(checked._1)))),
        timestamp = timestamp.toLong,
        timestampText = show(timestampJs),
        accounts = checked._2
      )

  private def topLevelKeys(genesis: JObj): Either[ReleaseRefusal, Unit] =
    val unknown = genesis.keys.filterNot(KnownTopKeys).sorted
    if unknown.isEmpty then Right(())
    else
      cannot("a genesis field", s"genesis.json declares top-level keys fukuii does not read: ${unknown.mkString(",")}")

  private def derivedZero(genesis: JObj): Either[ReleaseRefusal, Unit] =
    val nonZero = DerivedZeroKeys.filter { k =>
      genesis.get(k) match
        case None | Some(JNull) => false
        case Some(v @ (_: JStr | _: JNum)) =>
          val s = show(v)
          val bare = if s.startsWith("0x") || s.startsWith("0X") then s.substring(2) else s
          !ZeroText.matcher(bare).matches()
        case Some(_) => true
    }
    if nonZero.isEmpty then Right(())
    else cannot("a genesis field", s"genesis.json sets non-zero values fukuii does not read: ${nonZero.mkString(",")}")

  /** `mixHash`, or its lowercase spelling `mixhash`; both is an error unless they agree. Only `mixHash` is written. */
  private def mixHash(genesis: JObj): Either[ReleaseRefusal, String] =
    def present(k: String): Option[Js] = genesis.get(k).filter(_ != JNull)
    (present("mixHash"), present("mixhash")) match
      case (Some(a), Some(b)) if a != b =>
        cannot("a genesis field", "genesis.json has both mixHash and mixhash with different values")
      case (a, b) =>
        a.orElse(b) match
          case None                                              => Right(ZeroMixHash)
          case Some(JStr(s)) if MixHashText.matcher(s).matches() => Right(s)
          case Some(other) =>
            cannot(
              "a genesis field",
              s"genesis.mixHash must be 0x followed by 64 hex digits (32 bytes): '${show(other)}'"
            )

  private def extraData(genesis: JObj): Either[ReleaseRefusal, String] =
    genesis.get("extraData") match
      case None | Some(JNull) | Some(JStr(""))                 => Right("0x")
      case Some(JStr(s)) if ExtraDataText.matcher(s).matches() => Right(s)
      case Some(other) =>
        cannot("a genesis field", s"genesis.extraData must be 0x followed by at most 32 bytes of hex: '${show(other)}'")

  private def text(
      genesis: JObj,
      field: String,
      default: String,
      shape: Pattern,
      describe: String
  ): Either[ReleaseRefusal, String] =
    genesis.get(field) match
      case None | Some(JNull)                          => Right(default)
      case Some(JStr(s)) if shape.matcher(s).matches() => Right(s)
      case Some(other) => cannot("a genesis field", s"genesis.$field must be $describe: '${show(other)}'")

  private def optionalQuantity(genesis: JObj, field: String): Either[ReleaseRefusal, Option[BigInt]] =
    genesis.get(field) match
      case None | Some(JNull) => Right(None)
      case some               => quantity(field, some).map(Some(_))

  private def allocOf(genesis: JObj): Either[ReleaseRefusal, JObj] = genesis.get("alloc") match
    case Some(a: JObj) => Right(a)
    case _             => inconsistent("genesis.json has no alloc object")

  /** The alloc, checked and (for a `0X` code prefix) normalised, with its addresses. */
  private def checkAlloc(alloc: JObj): Either[ReleaseRefusal, (Vector[(String, Js)], Set[String])] =
    val notStrings = alloc.fields
      .map(_._2)
      .map {
        case JObj(fields) =>
          fields.count {
            case ("balance" | "nonce", v) => !v.isInstanceOf[JStr]
            case _                        => false
          }
        case _ => 0
      }
      .sum
    if notStrings != 0 then inconsistent(s"$notStrings alloc balance/nonce values are not JSON strings")
    else
      val seen = scala.collection.mutable.HashMap.empty[String, String]
      alloc.fields
        .foldLeft[Either[ReleaseRefusal, Vector[(String, Js)]]](Right(Vector.empty)) {
          case (Left(e), _) => Left(e)
          case (Right(done), (key, account)) =>
            val bare = if key.startsWith("0x") || key.startsWith("0X") then key.substring(2) else key
            if !AllocKeyDigits.matcher(bare).matches() then
              cannot("an alloc key", s"alloc key '$key' is not a hex address of 1 to 40 digits")
            else
              val address = normaliseAddress(key)
              seen.put(address, key) match
                case Some(earlier) =>
                  inconsistent(
                    s"alloc declares the account 0x$address twice ('$earlier' and '$key'): give each account one entry"
                  )
                case None => checkAccount(key, account).map(done :+ key -> _)
        }
        .map(fields => fields -> seen.keySet.toSet)

  private def checkAccount(key: String, account: Js): Either[ReleaseRefusal, Js] = account match
    case JObj(fields) =>
      val unknown = fields.map(_._1).filterNot(AccountKeys).sorted
      if unknown.nonEmpty then
        cannot("an alloc account", s"alloc account $key declares keys fukuii does not read: ${unknown.mkString(",")}")
      else
        fields
          .foldLeft[Either[ReleaseRefusal, Vector[(String, Js)]]](Right(Vector.empty)) {
            case (Left(e), _) => Left(e)
            case (Right(done), (name, value)) =>
              checkField(key, name, value).map(v => done :+ name -> v)
          }
          .map(JObj(_))
    case other =>
      cannot("an alloc account", s"alloc account $key is not an object: '${show(other)}'")

  private def checkField(key: String, name: String, value: Js): Either[ReleaseRefusal, Js] = name match
    case "balance" | "nonce" => uint256(s"alloc $key $name", value).map(_ => value)
    case "code" =>
      value match
        case JStr(s) if CodeText.matcher(s).matches() => Right(JStr("0x" + s.substring(2)))
        case other => cannot("an alloc value", s"alloc $key code is not 0x-hex of whole bytes: '${show(other)}'")
    case _ => // "storage"
      value match
        case JObj(slots) =>
          val seenKeys = scala.collection.mutable.HashSet.empty[BigInt]
          slots
            .foldLeft[Either[ReleaseRefusal, Unit]](Right(())) {
              case (Left(e), _) => Left(e)
              case (Right(_), (slot, v)) =>
                for
                  k <- storageWord(s"alloc $key storage key", JStr(slot))
                  _ <- storageWord(s"alloc $key storage value", v)
                  _ <- Either.cond(
                    seenKeys.add(k),
                    (),
                    ReleaseRefusal.Inconsistent(
                      s"alloc $key declares the storage slot '$slot' twice; the release is inconsistent"
                    )
                  )
                yield ()
            }
            .map(_ => value)
        case other => cannot("an alloc value", s"alloc $key storage is not an object: '${show(other)}'")
