package com.chipprbots.ethereum.jsonrpc.serialization

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.CustomSerializer
import org.json4s.DefaultFormats
import org.json4s.Extraction
import org.json4s.Formats
import org.json4s.JNull
import org.json4s.JString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.testmode.EthTransactionResponse

object JsonSerializers {

  implicit lazy val formats: Formats =
    DefaultFormats + UnformattedDataJsonSerializer + QuantitiesSerializer +
      OptionNoneToJNullSerializer + AddressJsonSerializer + EthTransactionResponseSerializer +
      makeTransactionResponseSerializer + makeTransactionReceiptResponseSerializer + makeBlockResponseSerializer

  object UnformattedDataJsonSerializer
      extends CustomSerializer[ByteString](_ =>
        (
          PartialFunction.empty,
          { case bs: ByteString => JString(s"0x${Hex.toHexString(bs.toArray)}") }
        )
      )

  object QuantitiesSerializer
      extends CustomSerializer[BigInt](_ =>
        (
          PartialFunction.empty,
          { case n: BigInt =>
            if (n == 0)
              JString("0x0")
            else
              JString(s"0x${Hex.toHexString(n.toByteArray).dropWhile(_ == '0')}")
          }
        )
      )

  object OptionNoneToJNullSerializer
      extends CustomSerializer[Option[_]](_ =>
        (
          PartialFunction.empty,
          { case None => JNull }
        )
      )

  object AddressJsonSerializer
      extends CustomSerializer[Address](_ =>
        (
          PartialFunction.empty,
          { case addr: Address => JString(s"0x${Hex.toHexString(addr.bytes.toArray)}") }
        )
      )

  object RpcErrorJsonSerializer
      extends CustomSerializer[JsonRpcError](_ =>
        (
          PartialFunction.empty,
          { case err: JsonRpcError => JsonEncoder.encode(err) }
        )
      )

  /** Specific EthTransactionResponse serializer. It's purpose is to encode the optional "to" field, as requested by
    * retesteth
    */
  object EthTransactionResponseSerializer
      extends CustomSerializer[EthTransactionResponse](_ =>
        (
          PartialFunction.empty,
          { case tx: EthTransactionResponse =>
            implicit val formats: Formats =
              DefaultFormats.preservingEmptyValues + UnformattedDataJsonSerializer + QuantitiesSerializer + AddressJsonSerializer
            Extraction.decompose(tx)
          }
        )
      )

  // Serializers for Scala 3 compatibility - delegate to manual encoders defined in JsonMethodsImplicits
  // These are added to formats after they're defined to avoid circular dependencies
  private def makeTransactionResponseSerializer: CustomSerializer[com.chipprbots.ethereum.jsonrpc.TransactionResponse] =
    new CustomSerializer[com.chipprbots.ethereum.jsonrpc.TransactionResponse](_ =>
      (
        PartialFunction.empty,
        { case tx: com.chipprbots.ethereum.jsonrpc.TransactionResponse =>
          import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionResponseJsonEncoder
          transactionResponseJsonEncoder.encodeJson(tx)
        }
      )
    )

  private def makeTransactionReceiptResponseSerializer
      : CustomSerializer[com.chipprbots.ethereum.jsonrpc.TransactionReceiptResponse] =
    new CustomSerializer[com.chipprbots.ethereum.jsonrpc.TransactionReceiptResponse](_ =>
      (
        PartialFunction.empty,
        { case receipt: com.chipprbots.ethereum.jsonrpc.TransactionReceiptResponse =>
          import com.chipprbots.ethereum.jsonrpc.EthTxJsonMethodsImplicits.transactionReceiptResponseJsonEncoder
          transactionReceiptResponseJsonEncoder.encodeJson(receipt)
        }
      )
    )

  private def makeBlockResponseSerializer: CustomSerializer[com.chipprbots.ethereum.jsonrpc.BlockResponse] =
    new CustomSerializer[com.chipprbots.ethereum.jsonrpc.BlockResponse](_ =>
      (
        PartialFunction.empty,
        { case block: com.chipprbots.ethereum.jsonrpc.BlockResponse =>
          import com.chipprbots.ethereum.jsonrpc.EthBlocksJsonMethodsImplicits.blockResponseEncoder
          blockResponseEncoder.encodeJson(block)
        }
      )
    )
}
