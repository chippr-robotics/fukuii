package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.ByteUtils

/** SNAP/1 protocol messages
  *
  * The SNAP protocol is a dependent satellite protocol of ETH that enables efficient state synchronization
  * by downloading account and storage ranges without intermediate Merkle trie nodes.
  *
  * See: https://github.com/ethereum/devp2p/blob/master/caps/snap.md
  *
  * Message codes:
  *   - 0x00: GetAccountRange
  *   - 0x01: AccountRange
  *   - 0x02: GetStorageRanges
  *   - 0x03: StorageRanges
  *   - 0x04: GetByteCodes
  *   - 0x05: ByteCodes
  *   - 0x06: GetTrieNodes
  *   - 0x07: TrieNodes
  */
object SNAP {

  /** Message codes for SNAP/1 protocol */
  object Codes {
    val GetAccountRangeCode: Int = 0x00
    val AccountRangeCode: Int = 0x01
    val GetStorageRangesCode: Int = 0x02
    val StorageRangesCode: Int = 0x03
    val GetByteCodesCode: Int = 0x04
    val ByteCodesCode: Int = 0x05
    val GetTrieNodesCode: Int = 0x06
    val TrieNodesCode: Int = 0x07
  }

  /** GetAccountRange message (0x00)
    *
    * Request for a range of accounts from a given account trie.
    *
    * @param requestId Request ID to match up responses
    * @param rootHash Root hash of the account trie to serve
    * @param startingHash Account hash of the first to retrieve
    * @param limitHash Account hash after which to stop serving data
    * @param responseBytes Soft limit at which to stop returning data
    * 
    * Note: This is the only SNAP message with full RLP encoding/decoding implemented.
    * Other messages have structure definitions only and will need encoding/decoding
    * implementation in future phases of SNAP sync development.
    */
  case class GetAccountRange(
      requestId: BigInt,
      rootHash: ByteString,
      startingHash: ByteString,
      limitHash: ByteString,
      responseBytes: BigInt
  ) extends Message {
    override def code: Int = Codes.GetAccountRangeCode
    override def toShortString: String = 
      s"GetAccountRange(reqId=$requestId, root=${rootHash.take(4).toHex}, start=${startingHash.take(4).toHex}, limit=${limitHash.take(4).toHex}, bytes=$responseBytes)"
  }

  object GetAccountRange {
    implicit class GetAccountRangeEnc(val underlyingMsg: GetAccountRange) 
        extends MessageSerializableImplicit[GetAccountRange](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.GetAccountRangeCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPValue(rootHash.toArray[Byte]),
          RLPValue(startingHash.toArray[Byte]),
          RLPValue(limitHash.toArray[Byte]),
          RLPValue(responseBytes.toByteArray)
        )
      }
    }

    implicit class GetAccountRangeDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetAccountRange: GetAccountRange = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              RLPValue(rootHashBytes),
              RLPValue(startingHashBytes),
              RLPValue(limitHashBytes),
              RLPValue(responseBytesBytes)
            ) =>
          GetAccountRange(
            ByteUtils.bytesToBigInt(requestIdBytes),
            ByteString(rootHashBytes),
            ByteString(startingHashBytes),
            ByteString(limitHashBytes),
            ByteUtils.bytesToBigInt(responseBytesBytes)
          )
        case rlpList: RLPList => 
          throw new RuntimeException(
            s"Cannot decode GetAccountRange. Expected RLPList[5] with structure " +
            s"[requestId, rootHash, startingHash, limitHash, responseBytes], " +
            s"but got RLPList[${rlpList.items.size}]"
          )
        case other => 
          throw new RuntimeException(
            s"Cannot decode GetAccountRange. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** AccountRange message (0x01)
    *
    * Response containing consecutive accounts and Merkle proofs for the range.
    *
    * @param requestId ID of the request this is a response for
    * @param accounts List of consecutive accounts from the trie (account hash -> account body)
    * @param proof List of trie nodes proving the account range
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class AccountRange(
      requestId: BigInt,
      accounts: Seq[(ByteString, Account)], // (accountHash, accountBody)
      proof: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.AccountRangeCode
    override def toShortString: String = 
      s"AccountRange(reqId=$requestId, accounts=${accounts.size}, proofNodes=${proof.size})"
  }

  /** GetStorageRanges message (0x02)
    *
    * Request for storage slots from given storage tries.
    *
    * @param requestId Request ID to match up responses
    * @param rootHash Root hash of the account trie to serve
    * @param accountHashes List of account hashes whose storage to retrieve
    * @param startingHash Storage slot hash to start from
    * @param limitHash Storage slot hash after which to stop
    * @param responseBytes Soft limit at which to stop returning data
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class GetStorageRanges(
      requestId: BigInt,
      rootHash: ByteString,
      accountHashes: Seq[ByteString],
      startingHash: ByteString,
      limitHash: ByteString,
      responseBytes: BigInt
  ) extends Message {
    override def code: Int = Codes.GetStorageRangesCode
    override def toShortString: String = 
      s"GetStorageRanges(reqId=$requestId, accounts=${accountHashes.size}, bytes=$responseBytes)"
  }

  /** StorageRanges message (0x03)
    *
    * Response containing storage slots and Merkle proofs.
    *
    * @param requestId ID of the request this is a response for
    * @param slots List of storage slot sets (one per account)
    * @param proof List of trie nodes proving the storage ranges
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class StorageRanges(
      requestId: BigInt,
      slots: Seq[Seq[(ByteString, ByteString)]], // Per account: (slotHash, slotValue)
      proof: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.StorageRangesCode
    override def toShortString: String = 
      s"StorageRanges(reqId=$requestId, slotSets=${slots.size}, proofNodes=${proof.size})"
  }

  /** GetByteCodes message (0x04)
    *
    * Request for contract bytecodes by their code hashes.
    *
    * @param requestId Request ID to match up responses
    * @param hashes List of bytecode hashes to retrieve
    * @param responseBytes Soft limit at which to stop returning data
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class GetByteCodes(
      requestId: BigInt,
      hashes: Seq[ByteString],
      responseBytes: BigInt
  ) extends Message {
    override def code: Int = Codes.GetByteCodesCode
    override def toShortString: String = 
      s"GetByteCodes(reqId=$requestId, hashes=${hashes.size}, bytes=$responseBytes)"
  }

  /** ByteCodes message (0x05)
    *
    * Response containing requested contract bytecodes.
    *
    * @param requestId ID of the request this is a response for
    * @param codes List of contract bytecodes
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class ByteCodes(
      requestId: BigInt,
      codes: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.ByteCodesCode
    override def toShortString: String = 
      s"ByteCodes(reqId=$requestId, codes=${codes.size})"
  }

  /** GetTrieNodes message (0x06)
    *
    * Request for trie nodes by their path and hash.
    *
    * @param requestId Request ID to match up responses
    * @param rootHash Root hash of the trie to serve nodes from
    * @param paths List of trie paths to retrieve (each path is a list of node hashes)
    * @param responseBytes Soft limit at which to stop returning data
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class GetTrieNodes(
      requestId: BigInt,
      rootHash: ByteString,
      paths: Seq[Seq[ByteString]], // List of paths (each path is a list of node hashes)
      responseBytes: BigInt
  ) extends Message {
    override def code: Int = Codes.GetTrieNodesCode
    override def toShortString: String = 
      s"GetTrieNodes(reqId=$requestId, paths=${paths.size}, bytes=$responseBytes)"
  }

  /** TrieNodes message (0x07)
    *
    * Response containing requested trie nodes.
    *
    * @param requestId ID of the request this is a response for
    * @param nodes List of trie nodes (RLP-encoded)
    * 
    * TODO: Implement RLP encoding/decoding for this message.
    */
  case class TrieNodes(
      requestId: BigInt,
      nodes: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.TrieNodesCode
    override def toShortString: String = 
      s"TrieNodes(reqId=$requestId, nodes=${nodes.size})"
  }
}
