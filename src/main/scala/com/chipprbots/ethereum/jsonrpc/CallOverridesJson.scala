package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.BlockOverrides
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.StateOverride
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams

/** The one decoder of the override parameters (execution-apis `StateOverrides` / `BlockOverrides`; go-ethereum
  * `internal/ethapi/override` `StateOverride` / `BlockOverrides`): the third and fourth parameters of eth_call and
  * eth_estimateGas, the third of eth_createAccessList, and a block state call's `stateOverrides` and `blockOverrides`
  * in eth_simulateV1. They decode into the data types eth_simulateV1 uses, so the one override implementation
  * ([[EthSimulateService.applyStateOverrides]]) serves them all.
  *
  * Values are decoded as go-ethereum's `hexutil` types decode them (`common/hexutil/json.go`), and every decode error
  * is -32602:
  *   - quantity (`hexutil.Big`: at most 256 bits; `hexutil.Uint64`): a JSON string; `""` is zero; otherwise `0x`, at
  *     least one digit and no leading zero digit
  *   - bytes (`hexutil.Bytes`): a JSON string; `""` or `0x` and an even number of hex digits
  *   - hash and address (`common.Hash`, `common.Address`): `0x` and exactly 32 or 20 bytes; a state override's address
  *     key and its `state` / `stateDiff` slot keys are the same, with the error texts of `UnmarshalText`
  *
  * Keys match as geth's `checkOverrideKeys` (simulate.go:114) matches them: case-insensitively, as `encoding/json`
  * does; a key that names no field is an error, and so are two keys that name the same field, both reported in sorted
  * key order. A key repeated with the same case keeps its last value, and a null value is a field not given.
  *
  * Deviations from geth, all fail-loudly:
  *   - eth_call, eth_estimateGas and eth_createAccessList reject an unknown key and two keys that name one field;
  *     geth's `encoding/json` ignores the first and keeps the last of the second. (eth_simulateV1 is geth's.)
  *   - a null entry in `withdrawals` is rejected; geth decodes it to a nil `*Withdrawal` it then dereferences.
  *
  * What differs by RPC is what the overrides can do. A single call has no block to hold a beacon root or withdrawals,
  * so geth's `BlockOverrides.Apply` refuses `beaconRoot` and then `withdrawals` there with `block override "<name>" is
  * not supported for this RPC method` (-32000), after the whole object has decoded; eth_simulateV1 carries both into
  * the block. A single call's `state` and `stateDiff` together are refused (-32000) while decoding; a simulated block
  * applies its overrides in turn, so there the service raises it, at the block that has them.
  *
  * The error texts are geth's where its source gives them: `json: cannot unmarshal <reason> into Go struct field
  * <Struct>.<field> of type <type>` for a hexutil decode error and `hex string has length n, want m for <type>` for a
  * fixed-size value. They were not compared against a running geth; the codes are what matter.
  */
object CallOverridesJson:

  // The fields of go-ethereum's override.BlockOverrides and OverrideAccount, by the name encoding/json matches a key
  // to: BlockOverrides has no `json` tags, so its Go names; OverrideAccount its tags. In struct order, so a key names
  // the first field it case-folds to. execution-apis: "A client MUST reject a key that it does not support with error
  // code -32602", which is why an unknown key is refused on every RPC. `Difficulty` decodes everywhere: geth's field
  // comment (override.go:132) calls it a no-op post-merge outside eth_simulateV1, whose service refuses it there.
  private val BlockFields =
    List(
      "Number",
      "Difficulty",
      "Time",
      "GasLimit",
      "FeeRecipient",
      "PrevRandao",
      "BaseFeePerGas",
      "BlobBaseFee",
      "BeaconRoot",
      "Withdrawals"
    )
  private val AccountFields = List("nonce", "code", "balance", "state", "stateDiff", "movePrecompileToAddress")
  private val WithdrawalFields = List("index", "validatorIndex", "address", "amount")

  private val BlockStruct = "BlockOverrides"
  private val AccountStruct = "OverrideAccount"

  // ---------------------------------------------------------------------------------------------
  // the entry points
  // ---------------------------------------------------------------------------------------------

  /** `stateOverrides` of eth_call, eth_estimateGas and eth_createAccessList (params[`argIndex`]). */
  def decodeStateOverrides(value: JValue, argIndex: Int): Either[JsonRpcError, Option[Map[Address, StateOverride]]] =
    decodeAccounts(value, argIndex, refuseStateAndDiff = true)

  /** `stateOverrides` of an eth_simulateV1 block state call. A value that is not an object is the error geth's
    * `simBlock` gives (`StateOverrides map[common.Address]json.RawMessage`).
    */
  def decodeSimulateStateOverrides(
      value: JValue,
      argIndex: Int
  ): Either[JsonRpcError, Option[Map[Address, StateOverride]]] =
    value match
      case JNull | JNothing | _: JObject => decodeAccounts(value, argIndex, refuseStateAndDiff = false)
      case other =>
        Left(
          err(
            argIndex,
            s"json: cannot unmarshal ${jsonKind(other)} into Go struct field .StateOverrides of type map[common.Address]json.RawMessage"
          )
        )

  /** `blockOverrides` of eth_call and eth_estimateGas (params[`argIndex`]): `beaconRoot` and `withdrawals` are refused,
    * as geth's `BlockOverrides.Apply` refuses them (override.go:148-153), once the whole object has decoded.
    */
  def decodeBlockOverrides(value: JValue, argIndex: Int): Either[JsonRpcError, Option[BlockOverrides]] =
    decodeBlock(value, argIndex).flatMap {
      case Some(o) if o.beaconRoot.isDefined  => Left(unsupported("beaconRoot"))
      case Some(o) if o.withdrawals.isDefined => Left(unsupported("withdrawals"))
      case other                              => Right(other)
    }

  /** `blockOverrides` of an eth_simulateV1 block state call: every field is carried into the simulated block. */
  def decodeSimulateBlockOverrides(value: JValue, argIndex: Int): Either[JsonRpcError, Option[BlockOverrides]] =
    decodeBlock(value, argIndex)

  private def unsupported(name: String): JsonRpcError =
    JsonRpcError.LogicError(s"""block override "$name" is not supported for this RPC method""")

  // ---------------------------------------------------------------------------------------------
  // the objects
  // ---------------------------------------------------------------------------------------------

  private def decodeAccounts(
      value: JValue,
      argIndex: Int,
      refuseStateAndDiff: Boolean
  ): Either[JsonRpcError, Option[Map[Address, StateOverride]]] =
    value match
      case JNull | JNothing => Right(None)
      case JObject(entries) =>
        traverse(entries) { case (key, v) =>
          for
            address <- fixedText(key, Address.Length, "Address").map(Address(_)).left.map(e => err(argIndex, e.text))
            account <- decodeAccount(v, address, argIndex, refuseStateAndDiff)
          yield address -> account
        }.map(accounts => Some(accounts.toMap))
      case other =>
        Left(err(argIndex, s"json: cannot unmarshal ${jsonKind(other)} into Go value of type override.StateOverride"))

  private def decodeAccount(
      value: JValue,
      address: Address,
      argIndex: Int,
      refuseStateAndDiff: Boolean
  ): Either[JsonRpcError, StateOverride] =
    value match
      // JSON null leaves the zero OverrideAccount, an override that changes nothing
      case JNull => Right(StateOverride())
      case JObject(fields) =>
        val where = s"state override for ${EthSimulateService.checksumAddress(address)}"
        knownKeys(fields, AccountFields, where, argIndex).flatMap { known =>
          val f = new Fields(known, argIndex, AccountStruct)
          for
            balance <- f.opt("balance")(big)
            nonce <- f.opt("nonce")(uint64)
            code <- f.opt("code")(bytes)
            state <- f.opt("state")(storage)
            stateDiff <- f.opt("stateDiff")(storage)
            // go-ethereum override.go:108: checked when applied, so -32000
            _ <- Either.cond(
              !refuseStateAndDiff || state.isEmpty || stateDiff.isEmpty,
              (),
              JsonRpcError.LogicError(
                s"account ${EthSimulateService.checksumAddress(address)} has both 'state' and 'stateDiff'"
              )
            )
            move <- f.opt("movePrecompileToAddress")(addressOf)
          yield StateOverride(balance, nonce, code, state, stateDiff, move)
        }
      case other =>
        Left(err(argIndex, s"json: cannot unmarshal ${jsonKind(other)} into Go value of type override.OverrideAccount"))

  // map[common.Hash]common.Hash: the keys decode with Hash.UnmarshalText, the values with Hash.UnmarshalJSON
  private def storage(value: JValue, at: At): Either[JsonRpcError, Map[BigInt, BigInt]] =
    value match
      case JObject(entries) =>
        traverse(entries) { case (k, v) =>
          for
            key <- fixedText(k, 32, "Hash").left.map(e => err(at.argIndex, e.text))
            word <- hash32(v, at)
          yield BigInt(1, key.toArray) -> BigInt(1, word.toArray)
        }.map(_.toMap)
      case other => Left(at.typeErr(jsonKind(other), "map[common.Hash]common.Hash"))

  private def decodeBlock(value: JValue, argIndex: Int): Either[JsonRpcError, Option[BlockOverrides]] =
    value match
      case JNull | JNothing => Right(None)
      case JObject(fields) =>
        knownKeys(fields, BlockFields, "block override", argIndex).flatMap { known =>
          val f = new Fields(known, argIndex, BlockStruct)
          for
            number <- f.opt("Number")(big)
            difficulty <- f.opt("Difficulty")(big)
            time <- f.opt("Time")(uint64)
            gasLimit <- f.opt("GasLimit")(uint64)
            feeRecipient <- f.opt("FeeRecipient")(addressOf)
            prevRandao <- f.opt("PrevRandao")(hash32)
            baseFee <- f.opt("BaseFeePerGas")(big)
            blobBaseFee <- f.opt("BlobBaseFee")(big)
            beaconRoot <- f.opt("BeaconRoot")(hash32)
            withdrawals <- f.opt("Withdrawals")(decodeWithdrawals)
          yield Some(
            BlockOverrides(
              number = number,
              time = time,
              gasLimit = gasLimit,
              feeRecipient = feeRecipient,
              prevRandao = prevRandao,
              baseFeePerGas = baseFee,
              blobBaseFee = blobBaseFee,
              difficulty = difficulty,
              withdrawals = withdrawals,
              beaconRoot = beaconRoot
            )
          )
        }
      case other =>
        Left(err(argIndex, s"json: cannot unmarshal ${jsonKind(other)} into Go value of type override.BlockOverrides"))

  // types.Withdrawals: go-ethereum's Withdrawal JSON (gen_withdrawal_json.go) has no required field, so one left out is
  // zero
  private def decodeWithdrawals(value: JValue, at: At): Either[JsonRpcError, Seq[Withdrawal]] =
    value match
      case JArray(items) => traverse(items)(decodeWithdrawal(_, at))
      case other         => Left(at.typeErr(jsonKind(other), "types.Withdrawals"))

  private def decodeWithdrawal(value: JValue, at: At): Either[JsonRpcError, Withdrawal] =
    value match
      case JObject(fields) =>
        // encoding/json applies the keys in document order, so the last of those naming a field wins and a later null
        // resets it; a key naming no field is ignored
        val named = fields.foldLeft(Map.empty[String, JValue]) { case (acc, (key, v)) =>
          WithdrawalFields.find(_.equalsIgnoreCase(key)).fold(acc)(acc.updated(_, v))
        }
        val f = new Fields(named.filter { case (_, v) => v != JNull }, at.argIndex, at.struct, s"${at.field}.")
        for
          index <- f.opt("index")(uint64)
          validatorIndex <- f.opt("validatorIndex")(uint64)
          address <- f.opt("address")(addressOf)
          amount <- f.opt("amount")(uint64)
        yield Withdrawal(
          index.getOrElse(BigInt(0)),
          validatorIndex.getOrElse(BigInt(0)),
          address.getOrElse(Address(0)),
          amount.getOrElse(BigInt(0))
        )
      case JNull => Left(err(at.argIndex, s"${at.struct}.${at.field} has a null entry"))
      case other =>
        Left(err(at.argIndex, s"json: cannot unmarshal ${jsonKind(other)} into Go value of type types.Withdrawal"))

  /** The known fields of an object as geth's `checkOverrideKeys` (simulate.go:114) finds them: `where` names the object
    * in the error. Keys are visited in sorted order; each must case-fold to a field of `known` that no other key names.
    * Returns the value of each field given, a null being a field not given.
    */
  private def knownKeys(
      fields: List[(String, JValue)],
      known: List[String],
      where: String,
      argIndex: Int
  ): Either[JsonRpcError, Map[String, JValue]] =
    val byKey = fields.toMap // a key repeated with the same case keeps its last value
    byKey.keys.toList.sorted
      .foldLeft[Either[JsonRpcError, Map[String, (String, JValue)]]](Right(Map.empty)) { (acc, key) =>
        acc.flatMap { seen =>
          known.find(_.equalsIgnoreCase(key)) match
            case None => Left(err(argIndex, s"""unknown field "$key" in $where"""))
            case Some(field) =>
              seen.get(field) match
                case Some((prev, _)) =>
                  Left(err(argIndex, s"""keys "$prev" and "$key" in $where name the same field"""))
                case None => Right(seen.updated(field, (key, byKey(key))))
        }
      }
      .map(_.collect { case (field, (_, v)) if v != JNull && v != JNothing => field -> v })

  // ---------------------------------------------------------------------------------------------
  // hexutil-strict primitives (go-ethereum common/hexutil/json.go)
  // ---------------------------------------------------------------------------------------------

  /** Where a value sits in the request, for its error text: params[`argIndex`], the Go struct and the field's path. */
  final private[jsonrpc] case class At(argIndex: Int, struct: String, field: String):
    /** geth wraps a hexutil `decError` in a `json.UnmarshalTypeError`, which the decoder places in its struct field. */
    def typeErr(reason: String, goType: String): JsonRpcError =
      err(argIndex, s"json: cannot unmarshal $reason into Go struct field $struct.$field of type $goType")

  /** The known fields of one object, decoded by name; `prefix` is the path of the object within `struct`. */
  final private class Fields(values: Map[String, JValue], argIndex: Int, struct: String, prefix: String = ""):
    def opt[A](field: String)(decode: (JValue, At) => Either[JsonRpcError, A]): Either[JsonRpcError, Option[A]] =
      values.get(field) match
        case None    => Right(None)
        case Some(v) => decode(v, At(argIndex, struct, prefix + field)).map(Some(_))

  private def err(argIndex: Int, msg: String): JsonRpcError = InvalidParams(
    s"invalid argument $argIndex: $msg"
  )

  /** The kind of a JSON value as `encoding/json` names it in an UnmarshalTypeError. */
  private def jsonKind(v: JValue): String = v match
    case _: JString                                    => "string"
    case _: JInt | _: JLong | _: JDouble | _: JDecimal => "number"
    case _: JBool                                      => "bool"
    case _: JArray                                     => "array"
    case _: JObject                                    => "object"
    case _                                             => "null"

  private def has0x(s: String): Boolean =
    s.length >= 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')
  // ASCII only: nothing on this path may throw on untrusted input
  private def isHex(s: String): Boolean =
    s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

  /** hexutil.Big: at most 256 bits. */
  private[jsonrpc] def big(v: JValue, at: At): Either[JsonRpcError, BigInt] =
    quantity(v, at, 64, "hex number > 256 bits", "*hexutil.Big")

  /** hexutil.Uint64. */
  private[jsonrpc] def uint64(v: JValue, at: At): Either[JsonRpcError, BigInt] =
    quantity(v, at, 16, "hex number > 64 bits", "hexutil.Uint64")

  // checkNumberText, then the range, then the digits (hexutil.go order). The width is judged on the digit count before
  // BigInt reads them: BigInt parses in quadratic time, so a multi-megabyte string is refused without being parsed.
  private def quantity(
      v: JValue,
      at: At,
      maxDigits: Int,
      rangeMsg: String,
      goType: String
  ): Either[JsonRpcError, BigInt] =
    v match
      case JString("") => Right(BigInt(0)) // "empty strings are allowed"
      case JString(s) =>
        if !has0x(s) then Left(at.typeErr("hex string without 0x prefix", goType))
        else
          val digits = s.substring(2)
          if digits.isEmpty then Left(at.typeErr("hex string \"0x\"", goType))
          else if digits.length > 1 && digits.charAt(0) == '0' then
            Left(at.typeErr("hex number with leading zero digits", goType))
          else if digits.length > maxDigits then Left(at.typeErr(rangeMsg, goType))
          else if !isHex(digits) then Left(at.typeErr("invalid hex string", goType))
          else Right(BigInt(digits, 16))
      case _ => Left(at.typeErr("non-string", goType))

  /** hexutil.Bytes. */
  private[jsonrpc] def bytes(v: JValue, at: At): Either[JsonRpcError, ByteString] =
    v match
      case JString(s) =>
        val raw = if s.isEmpty then Some("") else Option.when(has0x(s))(s.substring(2))
        raw match
          case None                         => Left(at.typeErr("hex string without 0x prefix", "hexutil.Bytes"))
          case Some(r) if r.length % 2 != 0 => Left(at.typeErr("hex string of odd length", "hexutil.Bytes"))
          case Some(r) if !isHex(r)         => Left(at.typeErr("invalid hex string", "hexutil.Bytes"))
          case Some(r)                      => Right(ByteString(Hex.decode(r)))
      case _ => Left(at.typeErr("non-string", "hexutil.Bytes"))

  /** common.Hash. */
  private[jsonrpc] def hash32(v: JValue, at: At): Either[JsonRpcError, ByteString] = fixed(v, at, 32, "common.Hash")

  /** common.Address. */
  private[jsonrpc] def addressOf(v: JValue, at: At): Either[JsonRpcError, Address] =
    fixed(v, at, Address.Length, "common.Address").map(Address(_))

  private def fixed(v: JValue, at: At, length: Int, goType: String): Either[JsonRpcError, ByteString] =
    v match
      case JString(s) =>
        fixedText(s, length, goType).left.map(e =>
          if e.isDecError then at.typeErr(e.text, goType) else err(at.argIndex, e.text)
        )
      case _ => Left(at.typeErr("non-string", goType))

  /** Why a fixed-size value was refused: `isDecError` for a hexutil `decError`, which geth's JSON layer wraps in an
    * UnmarshalTypeError (it names the struct field); the length error is a plain `fmt.Errorf`, so it is not.
    */
  final private case class FixedError(isDecError: Boolean, text: String)

  /** hexutil.UnmarshalFixedText: the empty string is length 0, so it is refused, as is any other wrong length;
    * `typname` is the type as the caller names it (a JSON value: `common.Hash`; a map key's UnmarshalText: `Hash`).
    */
  private def fixedText(s: String, length: Int, typname: String): Either[FixedError, ByteString] =
    if s.nonEmpty && !has0x(s) then Left(FixedError(true, "hex string without 0x prefix"))
    else
      val raw = if s.isEmpty then "" else s.substring(2)
      if raw.length % 2 != 0 then Left(FixedError(true, "hex string of odd length"))
      else if raw.length / 2 != length then
        Left(FixedError(false, s"hex string has length ${raw.length}, want ${length * 2} for $typname"))
      else if !isHex(raw) then Left(FixedError(true, "invalid hex string"))
      else Right(ByteString(Hex.decode(raw)))

  private def traverse[A, B](as: List[A])(f: A => Either[JsonRpcError, B]): Either[JsonRpcError, List[B]] =
    as.foldRight[Either[JsonRpcError, List[B]]](Right(Nil)) { (a, acc) =>
      for
        b <- f(a)
        rest <- acc
      yield b :: rest
    }
