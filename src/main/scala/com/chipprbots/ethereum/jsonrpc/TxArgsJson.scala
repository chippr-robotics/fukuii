package com.chipprbots.ethereum.jsonrpc

import java.util.Locale

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*

import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobVersionedHash
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams

/** JSON codec for [[TxArgs]] (execution-apis `GenericTransaction`).
  *
  * Decoding is as strict as go-ethereum's `hexutil` unmarshalling and reproduces its messages, wrapped the way geth's
  * RPC layer reports them: `invalid argument <n>: json: cannot unmarshal <reason> into Go struct field
  * TransactionArgs.<field> of type <type>` for quantity/bytes errors and `invalid argument <n>: hex string has length
  * ...` for fixed-size values. All decode errors are -32602.
  *
  * Rules (geth common/hexutil/json.go):
  *   - quantity: JSON string; `""` is zero; otherwise `0x`/`0X` prefix, at least one digit, no leading zero digit, at
  *     most 64 (uint64 fields: 16) hex digits; JSON numbers are rejected ("non-string")
  *   - bytes: JSON string; `""` and `"0x"` are empty; otherwise prefix and an even number of hex digits
  *   - address / hash32 / blob / commitment / proof: exact length (`""` is length 0 and therefore rejected, so `"to":
  *     ""` is an error, not contract creation)
  *   - keys match case-insensitively and, as in Go encoding/json, keys are applied in document order so the LAST
  *     case-folded match wins; a later `null` resets the field to absent (`{"gas":"0x1","GAS":null}` has no gas)
  *   - `null` is absent, everywhere; unknown fields are ignored (geth), although the schema says additionalProperties:
  *     false
  *   - known difference: Go decodes every occurrence of a key, so an invalid EARLIER duplicate is still an error there;
  *     here only the surviving (last) value is decoded
  *   - hex digits are ASCII only (`0-9a-fA-F`); nothing on this path throws on untrusted input
  *   - `type` (not decoded by geth): schema `byte`, 1-2 hex digits after `0x`
  */
object TxArgsJson:

  val BlobLength: Int = 131072 // EIP-4844 FIELD_ELEMENTS_PER_BLOB * 32
  val CommitmentLength: Int = 48
  val ProofLength: Int = 48

  private val StructName = "TransactionArgs"

  /** Decode one params entry. `argIndex` is the position in the params array, used only in error messages. */
  def decode(value: JValue, argIndex: Int = 0): Either[JsonRpcError, TxArgs] =
    value match
      case obj: JObject => decodeObject(obj, argIndex)
      case _ =>
        Left(
          InvalidParams(
            s"invalid argument $argIndex: json: cannot unmarshal non-object into Go value of type $StructName"
          )
        )

  /** [[decode]] then [[TxArgs.validateForSend]], with errors mapped through [[TxArgsError.toJsonRpcError]]. Entry point
    * for eth_sendTransaction / eth_signTransaction / eth_fillTransaction / personal_* (S2).
    */
  def decodeForSend(value: JValue, argIndex: Int = 0): Either[JsonRpcError, TxArgs] =
    decode(value, argIndex).flatMap(_.validateForSend.left.map(_.toJsonRpcError))

  /** [[decode]] then [[TxArgs.validateForCall]], with errors mapped through [[TxArgsError.toJsonRpcError]]. Entry point
    * for eth_call / eth_estimateGas / eth_createAccessList / debug_traceCall / trace_call (S3) and simulate (S4).
    */
  def decodeForCall(value: JValue, argIndex: Int = 0): Either[JsonRpcError, TxArgs] =
    decode(value, argIndex).flatMap(_.validateForCall.left.map(_.toJsonRpcError))

  def decodeObject(obj: JObject, argIndex: Int = 0): Either[JsonRpcError, TxArgs] =
    val d = new FieldDecoder(foldKeys(obj.obj), argIndex, StructName)
    for
      from <- d.opt("from")(d.address)
      to <- d.opt("to")(d.address)
      gas <- d.opt("gas")(d.uint64).map(_.map(GasAmount(_)))
      gasPrice <- d.opt("gasPrice")(d.big).map(_.map(GasPrice(_)))
      maxFee <- d.opt("maxFeePerGas")(d.big)
      maxTip <- d.opt("maxPriorityFeePerGas")(d.big)
      maxBlobFee <- d.opt("maxFeePerBlobGas")(d.big)
      value <- d.opt("value")(d.big)
      nonce <- d.opt("nonce")(d.uint64)
      data <- d.opt("data")(d.bytes)
      input <- d.opt("input")(d.bytes)
      accessList <- d.opt("accessList")(d.accessList)
      chainId <- d.opt("chainId")(d.big).map(_.map(ChainId(_)))
      txType <- d.opt("type")(d.txType)
      hashes <- d.opt("blobVersionedHashes")(d.list(_, _)(d.hash32)).map(_.map(_.map(BlobVersionedHash(_))))
      blobs <- d.opt("blobs")(d.list(_, _)(d.fixed(BlobLength, "kzg4844.Blob")))
      commitments <- d.opt("commitments")(d.list(_, _)(d.fixed(CommitmentLength, "kzg4844.Commitment")))
      proofs <- d.opt("proofs")(d.list(_, _)(d.fixed(ProofLength, "kzg4844.Proof")))
      authList <- d.opt("authorizationList")(d.authorizationList)
    yield TxArgs(
      from = from,
      to = to,
      gas = gas,
      gasPrice = gasPrice,
      maxFeePerGas = maxFee,
      maxPriorityFeePerGas = maxTip,
      maxFeePerBlobGas = maxBlobFee,
      value = value,
      nonce = nonce,
      data = data,
      input = input,
      accessList = accessList,
      chainId = chainId,
      txType = txType,
      blobVersionedHashes = hashes,
      blobs = blobs,
      commitments = commitments,
      proofs = proofs,
      authorizationList = authList
    )

  /** Encode as a `GenericTransaction` object. Absent fields are omitted; call data is emitted once, as `input`
    * ([[TxArgs.callData]]); `data` is never emitted.
    */
  def encode(args: TxArgs): JObject =
    val callData = if args.input.isDefined || args.data.isDefined then Some(args.callData) else None
    val fields: List[(String, Option[JValue])] = List(
      "type" -> args.txType.map(t => q(BigInt(t & 0xff))),
      "chainId" -> args.chainId.map(c => q(c.value)),
      "nonce" -> args.nonce.map(q),
      "from" -> args.from.map(a => b(a.bytes)),
      "to" -> args.to.map(a => b(a.bytes)),
      "gas" -> args.gas.map(g => q(g.value)),
      "gasPrice" -> args.gasPrice.map(p => q(p.value)),
      "maxPriorityFeePerGas" -> args.maxPriorityFeePerGas.map(q),
      "maxFeePerGas" -> args.maxFeePerGas.map(q),
      "maxFeePerBlobGas" -> args.maxFeePerBlobGas.map(q),
      "value" -> args.value.map(q),
      "input" -> callData.map(b),
      "accessList" -> args.accessList.map(encodeAccessList),
      "blobVersionedHashes" -> args.blobVersionedHashes.map(hs => JArray(hs.map(h => b(h.value)))),
      "blobs" -> args.blobs.map(xs => JArray(xs.map(b))),
      "commitments" -> args.commitments.map(xs => JArray(xs.map(b))),
      "proofs" -> args.proofs.map(xs => JArray(xs.map(b))),
      "authorizationList" -> args.authorizationList.map(encodeAuthorizationList)
    )
    JObject(fields.collect { case (k, Some(v)) => k -> v })

  def encodeAccessList(al: List[AccessListItem]): JArray =
    JArray(al.map { item =>
      JObject(
        "address" -> b(item.address.bytes),
        "storageKeys" -> JArray(item.storageKeys.map(k => JString(hash32Hex(k.value))))
      )
    })

  def encodeAuthorizationList(auths: List[SetCodeAuthorization]): JArray =
    JArray(auths.map { a =>
      JObject(
        "chainId" -> q(a.chainId),
        "address" -> b(a.address.bytes),
        "nonce" -> q(a.nonce),
        "yParity" -> q(a.v),
        "r" -> q(a.r),
        "s" -> q(a.s)
      )
    })

  /** Minimal-digit hex quantity, `0x0` for zero. */
  private def q(v: BigInt): JValue = JString(s"0x${v.toString(16)}")

  /** 0x-prefixed hex bytes, `0x` for empty. */
  private def b(bs: ByteString): JValue = JString(s"0x${Hex.toHexString(bs.toArray[Byte])}")

  /** Go encoding/json key semantics: case-insensitive, document order, last match wins (a final null included). */
  private def foldKeys(kvs: List[JField]): Map[String, JValue] =
    kvs.foldLeft(Map.empty[String, JValue]) { case (m, (k, v)) => m.updated(k.toLowerCase(Locale.ROOT), v) }

  private def hash32Hex(v: BigInt): String =
    val raw = v.toString(16)
    "0x" + ("0" * (64 - raw.length)) + raw

  // ---------------------------------------------------------------------------------------------
  // geth hexutil-compatible primitives
  // ---------------------------------------------------------------------------------------------

  /** Decodes the fields of one object.
    *
    * @param fields
    *   output of [[foldKeys]]: lower-cased keys, nulls retained (a null is resolved to absent at lookup)
    * @param structName
    *   Go type named in gencodec "missing required field" messages (`TransactionArgs`, `AccessTuple`,
    *   `SetCodeAuthorization`); type errors always name the top-level `TransactionArgs` struct, as Go does
    */
  final private class FieldDecoder(fields: Map[String, JValue], argIndex: Int, structName: String):
    type Dec[A] = (JValue, String) => Either[JsonRpcError, A]

    private def lookup(name: String): Option[JValue] =
      fields.get(name.toLowerCase(Locale.ROOT)).filter(v => v != JNull && v != JNothing)

    def opt[A](name: String)(dec: Dec[A]): Either[JsonRpcError, Option[A]] =
      lookup(name) match
        case None    => Right(None)
        case Some(v) => dec(v, name).map(Some(_))

    def req[A](name: String)(dec: Dec[A]): Either[JsonRpcError, A] =
      lookup(name) match
        case None    => Left(err(s"missing required field '$name' for $structName"))
        case Some(v) => dec(v, name)

    private def err(msg: String): JsonRpcError = InvalidParams(s"invalid argument $argIndex: $msg")

    // geth wraps hexutil decErrors in json.UnmarshalTypeError
    private def typeErr(reason: String, field: String, goType: String): JsonRpcError =
      err(s"json: cannot unmarshal $reason into Go struct field $StructName.$field of type $goType")

    private def str(v: JValue, field: String, goType: String): Either[JsonRpcError, String] =
      v match
        case JString(s) => Right(s)
        case _          => Left(typeErr("non-string", field, goType))

    private def has0x(s: String): Boolean =
      s.length >= 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X')
    // ASCII only: Character.digit also accepts Unicode digits (e.g. Arabic-Indic, fullwidth), which geth rejects and
    // on which Hex.decode throws
    private def isHex(s: String): Boolean =
      s.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

    private def number(maxDigits: Int, rangeMsg: String, goType: String): Dec[BigInt] = (v, field) =>
      str(v, field, goType).flatMap { s =>
        if s.isEmpty then Right(BigInt(0)) // checkNumberText: empty strings are allowed
        else if !has0x(s) then Left(typeErr("hex string without 0x prefix", field, goType))
        else
          val digits = s.substring(2)
          if digits.isEmpty then Left(typeErr("hex string \"0x\"", field, goType))
          else if digits.length > 1 && digits.charAt(0) == '0' then
            Left(typeErr("hex number with leading zero digits", field, goType))
          else if digits.length > maxDigits then Left(typeErr(rangeMsg, field, goType))
          else if !isHex(digits) then Left(typeErr("invalid hex string", field, goType))
          else Right(BigInt(digits, 16))
      }

    val big: Dec[BigInt] = number(64, "hex number > 256 bits", "hexutil.Big")
    val uint64: Dec[BigInt] = number(16, "hex number > 64 bits", "hexutil.Uint64")
    val u256: Dec[BigInt] = number(64, "hex number > 256 bits", "hexutil.U256")

    val bytes: Dec[ByteString] = (v, field) =>
      str(v, field, "hexutil.Bytes").flatMap { s =>
        val raw =
          if s.isEmpty then Right("")
          else if has0x(s) then Right(s.substring(2))
          else Left("hex string without 0x prefix")
        raw match
          case Left(reason)                  => Left(typeErr(reason, field, "hexutil.Bytes"))
          case Right(r) if r.length % 2 != 0 => Left(typeErr("hex string of odd length", field, "hexutil.Bytes"))
          case Right(r) if !isHex(r)         => Left(typeErr("invalid hex string", field, "hexutil.Bytes"))
          case Right(r)                      => Right(ByteString(Hex.decode(r)))
      }

    /** geth UnmarshalFixedText: errors here are not decErrors, so they are reported unwrapped. */
    def fixed(length: Int, goType: String): Dec[ByteString] = (v, field) =>
      v match
        case JString(s) =>
          if s.nonEmpty && !has0x(s) then Left(typeErr("hex string without 0x prefix", field, goType))
          else
            val raw = if s.isEmpty then "" else s.substring(2)
            if raw.length % 2 != 0 then Left(typeErr("hex string of odd length", field, goType))
            else if raw.length / 2 != length then
              Left(err(s"hex string has length ${raw.length}, want ${length * 2} for $goType"))
            else if !isHex(raw) then Left(typeErr("invalid hex string", field, goType))
            else Right(ByteString(Hex.decode(raw)))
        case _ => Left(typeErr("non-string", field, goType))

    val address: Dec[Address] = (v, field) => fixed(Address.Length, "common.Address")(v, field).map(Address(_))
    val hash32: Dec[ByteString] = fixed(32, "common.Hash")

    /** execution-apis `byte`: `^0x([0-9a-fA-F]?){1,2}$`. */
    val txType: Dec[Byte] = (v, field) =>
      v match
        case JString(s) if has0x(s) && s.length >= 3 && s.length <= 4 && isHex(s.substring(2)) =>
          Right(Integer.parseInt(s.substring(2), 16).toByte)
        case JString(_) => Left(err(s"invalid transaction type for field \"$field\": expected 0x-prefixed byte"))
        case _          => Left(typeErr("non-string", field, "hexutil.Uint64"))

    def list[A](v: JValue, field: String)(elem: Dec[A]): Either[JsonRpcError, List[A]] =
      v match
        case JArray(items) => traverse(items)(elem(_, field))
        case _             => Left(err(s"json: cannot unmarshal non-array into Go struct field $StructName.$field"))

    private def objects(v: JValue, field: String): Either[JsonRpcError, List[Map[String, JValue]]] =
      list(v, field) { (item, f) =>
        item match
          case JObject(kvs) => Right(foldKeys(kvs))
          case _            => Left(err(s"json: cannot unmarshal non-object into Go struct field $StructName.$f"))
      }

    val accessList: Dec[List[AccessListItem]] = (v, field) =>
      objects(v, field).flatMap { entries =>
        traverse(entries) { kv =>
          val e = new FieldDecoder(kv, argIndex, "AccessTuple")
          for
            addr <- e.req("address")((x, _) => address(x, s"$field.address"))
            keys <- e.req("storageKeys")((x, _) => list(x, s"$field.storageKeys")(hash32))
          yield AccessListItem(addr, keys.map(k => StorageKey(BigInt(1, k.toArray[Byte]))))
        }
      }

    val authorizationList: Dec[List[SetCodeAuthorization]] = (v, field) =>
      objects(v, field).flatMap { entries =>
        traverse(entries) { kv =>
          val e = new FieldDecoder(kv, argIndex, "SetCodeAuthorization")
          for
            chainId <- e.req("chainId")((x, _) => u256(x, s"$field.chainId"))
            addr <- e.req("address")((x, _) => address(x, s"$field.address"))
            nonce <- e.req("nonce")((x, _) => uint64(x, s"$field.nonce"))
            yParity <- e.req("yParity")((x, _) => uint64(x, s"$field.yParity"))
            _ <- Either.cond(yParity <= 0xff, (), err(s"yParity out of range for $field: ${yParity}"))
            r <- e.req("r")((x, _) => u256(x, s"$field.r"))
            s <- e.req("s")((x, _) => u256(x, s"$field.s"))
          yield SetCodeAuthorization(chainId, addr, nonce, yParity, r, s)
        }
      }

  private def traverse[A, B](as: List[A])(f: A => Either[JsonRpcError, B]): Either[JsonRpcError, List[B]] =
    as.foldRight[Either[JsonRpcError, List[B]]](Right(Nil)) { (a, acc) =>
      for
        b <- f(a)
        rest <- acc
      yield b :: rest
    }
