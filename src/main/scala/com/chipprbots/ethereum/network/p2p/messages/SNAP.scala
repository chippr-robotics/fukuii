package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp
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

  object AccountRange {
    import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits._
    
    implicit class AccountRangeEnc(val underlyingMsg: AccountRange)
        extends MessageSerializableImplicit[AccountRange](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.AccountRangeCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        // Encode accounts as list of [hash, body] pairs
        val accountsList = accounts.map { case (hash, account) =>
          RLPList(
            RLPValue(hash.toArray[Byte]),
            account.toRLPEncodable
          )
        }
        // Encode proof as list of byte arrays
        val proofList = proof.map(p => RLPValue(p.toArray[Byte]))
        
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPList(accountsList*),
          RLPList(proofList*)
        )
      }
    }

    implicit class AccountRangeDec(val bytes: Array[Byte]) extends AnyVal {
      def toAccountRange: AccountRange = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              accountsList: RLPList,
              proofList: RLPList
            ) =>
          val accounts = accountsList.items.map {
            case RLPList(RLPValue(hashBytes), accountRLP: RLPEncodeable) =>
              val hash = ByteString(hashBytes)
              val account = rlp.encode(accountRLP).toAccount
              (hash, account)
            case other =>
              throw new RuntimeException(
                s"Cannot decode account data. Expected RLPList[2] [hash, body], got: ${other.getClass.getSimpleName}"
              )
          }
          val proof = proofList.items.map {
            case RLPValue(proofBytes) => ByteString(proofBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode proof node. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          AccountRange(
            ByteUtils.bytesToBigInt(requestIdBytes),
            accounts,
            proof
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode AccountRange. Expected RLPList[3] with structure " +
            s"[requestId, accounts, proof], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode AccountRange. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
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

  object GetStorageRanges {
    implicit class GetStorageRangesEnc(val underlyingMsg: GetStorageRanges)
        extends MessageSerializableImplicit[GetStorageRanges](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.GetStorageRangesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        val accountHashesList = accountHashes.map(h => RLPValue(h.toArray[Byte]))
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPValue(rootHash.toArray[Byte]),
          RLPList(accountHashesList*),
          RLPValue(startingHash.toArray[Byte]),
          RLPValue(limitHash.toArray[Byte]),
          RLPValue(responseBytes.toByteArray)
        )
      }
    }

    implicit class GetStorageRangesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetStorageRanges: GetStorageRanges = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              RLPValue(rootHashBytes),
              accountHashesList: RLPList,
              RLPValue(startingHashBytes),
              RLPValue(limitHashBytes),
              RLPValue(responseBytesBytes)
            ) =>
          val accountHashes = accountHashesList.items.map {
            case RLPValue(hashBytes) => ByteString(hashBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode account hash. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          GetStorageRanges(
            ByteUtils.bytesToBigInt(requestIdBytes),
            ByteString(rootHashBytes),
            accountHashes,
            ByteString(startingHashBytes),
            ByteString(limitHashBytes),
            ByteUtils.bytesToBigInt(responseBytesBytes)
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode GetStorageRanges. Expected RLPList[6] with structure " +
            s"[requestId, rootHash, accounts, startingHash, limitHash, responseBytes], " +
            s"but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode GetStorageRanges. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** StorageRanges message (0x03)
    *
    * Response containing storage slots and Merkle proofs.
    *
    * @param requestId ID of the request this is a response for
    * @param slots List of storage slot sets (one per account)
    * @param proof List of trie nodes proving the storage ranges
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

  object StorageRanges {
    implicit class StorageRangesEnc(val underlyingMsg: StorageRanges)
        extends MessageSerializableImplicit[StorageRanges](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.StorageRangesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        // Encode slots as list of lists of [hash, value] pairs
        val slotsList = slots.map { accountSlots =>
          val slotPairs = accountSlots.map { case (hash, value) =>
            RLPList(
              RLPValue(hash.toArray[Byte]),
              RLPValue(value.toArray[Byte])
            )
          }
          RLPList(slotPairs*)
        }
        // Encode proof as list of byte arrays
        val proofList = proof.map(p => RLPValue(p.toArray[Byte]))
        
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPList(slotsList*),
          RLPList(proofList*)
        )
      }
    }

    implicit class StorageRangesDec(val bytes: Array[Byte]) extends AnyVal {
      def toStorageRanges: StorageRanges = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              slotsList: RLPList,
              proofList: RLPList
            ) =>
          val slots = slotsList.items.map {
            case accountSlots: RLPList =>
              accountSlots.items.map {
                case RLPList(RLPValue(hashBytes), RLPValue(valueBytes)) =>
                  (ByteString(hashBytes), ByteString(valueBytes))
                case other =>
                  throw new RuntimeException(
                    s"Cannot decode storage slot. Expected RLPList[2] [hash, value], got: ${other.getClass.getSimpleName}"
                  )
              }
            case other =>
              throw new RuntimeException(
                s"Cannot decode storage slots for account. Expected RLPList, got: ${other.getClass.getSimpleName}"
              )
          }
          val proof = proofList.items.map {
            case RLPValue(proofBytes) => ByteString(proofBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode proof node. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          StorageRanges(
            ByteUtils.bytesToBigInt(requestIdBytes),
            slots,
            proof
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode StorageRanges. Expected RLPList[3] with structure " +
            s"[requestId, slots, proof], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode StorageRanges. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** GetByteCodes message (0x04)
    *
    * Request for contract bytecodes by their code hashes.
    *
    * @param requestId Request ID to match up responses
    * @param hashes List of bytecode hashes to retrieve
    * @param responseBytes Soft limit at which to stop returning data
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

  object GetByteCodes {
    implicit class GetByteCodesEnc(val underlyingMsg: GetByteCodes)
        extends MessageSerializableImplicit[GetByteCodes](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.GetByteCodesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        val hashesList = hashes.map(h => RLPValue(h.toArray[Byte]))
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPList(hashesList*),
          RLPValue(responseBytes.toByteArray)
        )
      }
    }

    implicit class GetByteCodesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetByteCodes: GetByteCodes = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              hashesList: RLPList,
              RLPValue(responseBytesBytes)
            ) =>
          val hashes = hashesList.items.map {
            case RLPValue(hashBytes) => ByteString(hashBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode bytecode hash. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          GetByteCodes(
            ByteUtils.bytesToBigInt(requestIdBytes),
            hashes,
            ByteUtils.bytesToBigInt(responseBytesBytes)
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode GetByteCodes. Expected RLPList[3] with structure " +
            s"[requestId, hashes, responseBytes], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode GetByteCodes. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** ByteCodes message (0x05)
    *
    * Response containing requested contract bytecodes.
    *
    * @param requestId ID of the request this is a response for
    * @param codes List of contract bytecodes
    */
  case class ByteCodes(
      requestId: BigInt,
      codes: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.ByteCodesCode
    override def toShortString: String = 
      s"ByteCodes(reqId=$requestId, codes=${codes.size})"
  }

  object ByteCodes {
    implicit class ByteCodesEnc(val underlyingMsg: ByteCodes)
        extends MessageSerializableImplicit[ByteCodes](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.ByteCodesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        val codesList = codes.map(c => RLPValue(c.toArray[Byte]))
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPList(codesList*)
        )
      }
    }

    implicit class ByteCodesDec(val bytes: Array[Byte]) extends AnyVal {
      def toByteCodes: ByteCodes = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              codesList: RLPList
            ) =>
          val codes = codesList.items.map {
            case RLPValue(codeBytes) => ByteString(codeBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode bytecode. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          ByteCodes(
            ByteUtils.bytesToBigInt(requestIdBytes),
            codes
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode ByteCodes. Expected RLPList[2] with structure " +
            s"[requestId, codes], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode ByteCodes. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** GetTrieNodes message (0x06)
    *
    * Request for trie nodes by their path and hash.
    *
    * @param requestId Request ID to match up responses
    * @param rootHash Root hash of the trie to serve nodes from
    * @param paths List of trie paths to retrieve (each path is a list of node hashes)
    * @param responseBytes Soft limit at which to stop returning data
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

  object GetTrieNodes {
    implicit class GetTrieNodesEnc(val underlyingMsg: GetTrieNodes)
        extends MessageSerializableImplicit[GetTrieNodes](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.GetTrieNodesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        // Encode paths as list of lists of node hashes
        val pathsList = paths.map { path =>
          val nodeHashes = path.map(h => RLPValue(h.toArray[Byte]))
          RLPList(nodeHashes*)
        }
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPValue(rootHash.toArray[Byte]),
          RLPList(pathsList*),
          RLPValue(responseBytes.toByteArray)
        )
      }
    }

    implicit class GetTrieNodesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetTrieNodes: GetTrieNodes = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              RLPValue(rootHashBytes),
              pathsList: RLPList,
              RLPValue(responseBytesBytes)
            ) =>
          val paths = pathsList.items.map {
            case path: RLPList =>
              path.items.map {
                case RLPValue(hashBytes) => ByteString(hashBytes)
                case other =>
                  throw new RuntimeException(
                    s"Cannot decode node hash in path. Expected RLPValue, got: ${other.getClass.getSimpleName}"
                  )
              }
            case other =>
              throw new RuntimeException(
                s"Cannot decode path. Expected RLPList, got: ${other.getClass.getSimpleName}"
              )
          }
          GetTrieNodes(
            ByteUtils.bytesToBigInt(requestIdBytes),
            ByteString(rootHashBytes),
            paths,
            ByteUtils.bytesToBigInt(responseBytesBytes)
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode GetTrieNodes. Expected RLPList[4] with structure " +
            s"[requestId, rootHash, paths, responseBytes], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode GetTrieNodes. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }

  /** TrieNodes message (0x07)
    *
    * Response containing requested trie nodes.
    *
    * @param requestId ID of the request this is a response for
    * @param nodes List of trie nodes (RLP-encoded)
    */
  case class TrieNodes(
      requestId: BigInt,
      nodes: Seq[ByteString]
  ) extends Message {
    override def code: Int = Codes.TrieNodesCode
    override def toShortString: String = 
      s"TrieNodes(reqId=$requestId, nodes=${nodes.size})"
  }

  object TrieNodes {
    implicit class TrieNodesEnc(val underlyingMsg: TrieNodes)
        extends MessageSerializableImplicit[TrieNodes](underlyingMsg) with RLPSerializable {
      override def code: Int = Codes.TrieNodesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        val nodesList = nodes.map(n => RLPValue(n.toArray[Byte]))
        RLPList(
          RLPValue(requestId.toByteArray),
          RLPList(nodesList*)
        )
      }
    }

    implicit class TrieNodesDec(val bytes: Array[Byte]) extends AnyVal {
      def toTrieNodes: TrieNodes = rawDecode(bytes) match {
        case RLPList(
              RLPValue(requestIdBytes),
              nodesList: RLPList
            ) =>
          val nodes = nodesList.items.map {
            case RLPValue(nodeBytes) => ByteString(nodeBytes)
            case other =>
              throw new RuntimeException(
                s"Cannot decode trie node. Expected RLPValue, got: ${other.getClass.getSimpleName}"
              )
          }
          TrieNodes(
            ByteUtils.bytesToBigInt(requestIdBytes),
            nodes
          )
        case rlpList: RLPList =>
          throw new RuntimeException(
            s"Cannot decode TrieNodes. Expected RLPList[2] with structure " +
            s"[requestId, nodes], but got RLPList[${rlpList.items.size}]"
          )
        case other =>
          throw new RuntimeException(
            s"Cannot decode TrieNodes. Expected RLPList, got: ${other.getClass.getSimpleName}"
          )
      }
    }
  }
}
