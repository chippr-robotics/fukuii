package com.chipprbots.ethereum.jsonrpc

import java.util.Locale

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.BlockOverrides
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.StateOverride
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams

/** Decoders for the third and fourth parameters of eth_call / eth_estimateGas (execution-apis `StateOverrides` /
  * `BlockOverrides`; go-ethereum `internal/ethapi/override` `StateOverride` / `BlockOverrides`), into the same data
  * types eth_simulateV1 uses so the one override implementation ([[EthSimulateService.applyStateOverrides]]) serves
  * both.
  *
  * Values are decoded as strictly as geth's hexutil types (quantities need `0x`, no leading zeros; hashes and addresses
  * are exact length) and every error is -32602. Two deliberate deviations from geth, both fail-loudly:
  *   - an unknown key is rejected (geth's encoding/json ignores it), as fukuii's eth_simulateV1 decoder and the
  *     execution-apis schema (`additionalProperties: false`) do;
  *   - keys match exactly (geth matches case-insensitively).
  *
  * `beaconRoot` and `withdrawals` are part of geth's BlockOverrides but cannot be applied to a single call; geth
  * answers them with `block override "<name>" is not supported for this RPC method` (-32000), as here.
  */
object CallOverridesJson:

  private val StateKeys = Set("balance", "nonce", "code", "state", "stateDiff", "movePrecompileToAddress")
  private val BlockKeys =
    Set(
      "number",
      "difficulty",
      "time",
      "gasLimit",
      "feeRecipient",
      "prevRandao",
      "baseFeePerGas",
      "blobBaseFee",
      "beaconRoot",
      "withdrawals"
    )

  def decodeStateOverrides(value: JValue, argIndex: Int): Either[JsonRpcError, Option[Map[Address, StateOverride]]] =
    value match
      case JNull | JNothing => Right(None)
      case JObject(entries) =>
        traverse(entries) { case (key, v) =>
          for
            address <- fixed(JString(key), Address.Length, "common.Address", argIndex).map(Address(_))
            ov <- decodeAccount(v, address, argIndex)
          yield address -> ov
        }.map(list => Some(list.toMap))
      case _ => Left(err(argIndex, "json: cannot unmarshal non-object into Go value of type override.StateOverride"))

  private def decodeAccount(value: JValue, address: Address, argIndex: Int): Either[JsonRpcError, StateOverride] =
    value match
      case JObject(fields) =>
        val m = fields.toMap.filter { case (_, v) => v != JNull && v != JNothing }
        for
          _ <- unknown(fields.map(_._1), StateKeys, s"state override for $address", argIndex)
          balance <- opt(m.get("balance"))(quantity(_, 64, argIndex))
          nonce <- opt(m.get("nonce"))(quantity(_, 16, argIndex))
          code <- opt(m.get("code"))(bytes(_, argIndex))
          state <- opt(m.get("state"))(storage(_, argIndex))
          stateDiff <- opt(m.get("stateDiff"))(storage(_, argIndex))
          // go-ethereum override.go: checked when applied, so -32000 there and here
          _ <- Either.cond(
            state.isEmpty || stateDiff.isEmpty,
            (),
            JsonRpcError.LogicError(
              s"account ${EthSimulateService.checksumAddress(address)} has both 'state' and 'stateDiff'"
            )
          )
          move <- opt(m.get("movePrecompileToAddress"))(
            fixed(_, Address.Length, "common.Address", argIndex).map(Address(_))
          )
        yield StateOverride(balance, nonce, code, state, stateDiff, move)
      case _ =>
        Left(err(argIndex, "json: cannot unmarshal non-object into Go value of type override.OverrideAccount"))

  private def storage(value: JValue, argIndex: Int): Either[JsonRpcError, Map[BigInt, BigInt]] =
    value match
      case JObject(entries) =>
        traverse(entries) { case (k, v) =>
          for
            key <- fixed(JString(k), 32, "common.Hash", argIndex)
            word <- fixed(v, 32, "common.Hash", argIndex)
          yield BigInt(1, key.toArray) -> BigInt(1, word.toArray)
        }.map(_.toMap)
      case _ =>
        Left(err(argIndex, "json: cannot unmarshal non-object into Go value of type map[common.Hash]common.Hash"))

  def decodeBlockOverrides(value: JValue, argIndex: Int): Either[JsonRpcError, Option[BlockOverrides]] =
    value match
      case JNull | JNothing => Right(None)
      case JObject(fields) =>
        val m = fields.toMap.filter { case (_, v) => v != JNull && v != JNothing }
        for
          _ <- unknown(fields.map(_._1), BlockKeys, "block override", argIndex)
          _ <- List("beaconRoot", "withdrawals").find(m.contains) match
            case Some(k) =>
              Left(JsonRpcError.LogicError(s"""block override "$k" is not supported for this RPC method"""))
            case None => Right(())
          number <- opt(m.get("number"))(quantity(_, 64, argIndex))
          difficulty <- opt(m.get("difficulty"))(quantity(_, 64, argIndex))
          time <- opt(m.get("time"))(quantity(_, 16, argIndex))
          gasLimit <- opt(m.get("gasLimit"))(quantity(_, 16, argIndex))
          feeRecipient <- opt(m.get("feeRecipient"))(
            fixed(_, Address.Length, "common.Address", argIndex).map(Address(_))
          )
          prevRandao <- opt(m.get("prevRandao"))(fixed(_, 32, "common.Hash", argIndex))
          baseFee <- opt(m.get("baseFeePerGas"))(quantity(_, 64, argIndex))
          blobBaseFee <- opt(m.get("blobBaseFee"))(quantity(_, 64, argIndex))
        yield Some(
          BlockOverrides(
            number = number,
            time = time,
            gasLimit = gasLimit,
            feeRecipient = feeRecipient,
            prevRandao = prevRandao,
            baseFeePerGas = baseFee,
            blobBaseFee = blobBaseFee,
            difficulty = difficulty
          )
        )
      case _ => Left(err(argIndex, "json: cannot unmarshal non-object into Go value of type override.BlockOverrides"))

  // ---------------------------------------------------------------------------------------------
  // hexutil-strict primitives (go-ethereum common/hexutil/json.go)
  // ---------------------------------------------------------------------------------------------

  private def err(argIndex: Int, msg: String): JsonRpcError = InvalidParams(s"invalid argument $argIndex: $msg")

  private def unknown(
      keys: List[String],
      known: Set[String],
      where: String,
      argIndex: Int
  ): Either[JsonRpcError, Unit] =
    keys.find(k => !known.contains(k)) match
      case Some(k) => Left(err(argIndex, s"""unknown field "$k" in $where"""))
      case None    => Right(())

  private def opt[A](v: Option[JValue])(dec: JValue => Either[JsonRpcError, A]): Either[JsonRpcError, Option[A]] =
    v match
      case None    => Right(None)
      case Some(x) => dec(x).map(Some(_))

  private def has0x(s: String): Boolean =
    s.length >= 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')
  private def isHex(s: String): Boolean =
    s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

  private def quantity(v: JValue, maxDigits: Int, argIndex: Int): Either[JsonRpcError, BigInt] =
    v match
      case JString(s) =>
        if !has0x(s) then Left(err(argIndex, "hex string without 0x prefix"))
        else
          val digits = s.substring(2)
          if digits.isEmpty then Left(err(argIndex, "hex string \"0x\""))
          else if digits.length > 1 && digits.charAt(0) == '0' then
            Left(err(argIndex, "hex number with leading zero digits"))
          else if digits.length > maxDigits then
            Left(err(argIndex, s"hex number > ${if maxDigits == 16 then 64 else 256} bits"))
          else if !isHex(digits) then Left(err(argIndex, "invalid hex string"))
          else Right(BigInt(digits, 16))
      case _ => Left(err(argIndex, "json: cannot unmarshal non-string into Go value of type hexutil.Big"))

  private def bytes(v: JValue, argIndex: Int): Either[JsonRpcError, ByteString] =
    v match
      case JString(s) =>
        val raw = if s.isEmpty then "" else if has0x(s) then s.substring(2) else null
        if raw == null then Left(err(argIndex, "hex string without 0x prefix"))
        else if raw.length % 2 != 0 then Left(err(argIndex, "hex string of odd length"))
        else if !isHex(raw) then Left(err(argIndex, "invalid hex string"))
        else Right(ByteString(Hex.decode(raw)))
      case _ => Left(err(argIndex, "json: cannot unmarshal non-string into Go value of type hexutil.Bytes"))

  private def fixed(v: JValue, length: Int, goType: String, argIndex: Int): Either[JsonRpcError, ByteString] =
    v match
      case JString(s) =>
        if !has0x(s) then Left(err(argIndex, s"hex string without 0x prefix for $goType"))
        else
          val raw = s.substring(2)
          if raw.length % 2 != 0 then Left(err(argIndex, s"hex string of odd length for $goType"))
          else if raw.length / 2 != length then
            Left(err(argIndex, s"hex string has length ${raw.length}, want ${length * 2} for $goType"))
          else if !isHex(raw) then Left(err(argIndex, s"invalid hex string for $goType"))
          else Right(ByteString(Hex.decode(raw.toLowerCase(Locale.ROOT))))
      case _ => Left(err(argIndex, s"json: cannot unmarshal non-string into Go value of type $goType"))

  private def traverse[A, B](as: List[A])(f: A => Either[JsonRpcError, B]): Either[JsonRpcError, List[B]] =
    as.foldRight[Either[JsonRpcError, List[B]]](Right(Nil)) { (a, acc) =>
      for
        b <- f(a)
        rest <- acc
      yield b :: rest
    }
