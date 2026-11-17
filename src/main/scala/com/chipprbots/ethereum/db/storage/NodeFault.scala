package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals

/** What is wrong with what a store holds under a hash. */
enum NodeFault:
  case Absent

  /** The bytes do not hash to their key. */
  case BadKeccak

  /** The entry or the node in it does not decode. */
  case Undecodable

  def isBad: Boolean = this != Absent

  def describe: String = this match
    case Absent      => "is absent"
    case BadKeccak   => "fails its hash"
    case Undecodable => "does not decode"

/** The one judgement of a stored trie node, for every reader that must not believe a bad one: the background audit's
  * walk (spec 022 T053), the heal coordinator's frontier walk, and the stores' own delete of a bad entry (S-1). Keccak
  * first: bad bytes are never decoded, and their children never believed.
  *
  * Two questions, which differ on one case. [[check]]: is this the node, decoded? [[provablyBad]]: may a delete take
  * the entry? Bytes that hash to their key but do not decode answer no to both. They are the node `hash` names, which
  * keccak binds, so a peer would serve the same bytes, and they are never deleted (the user's rule, 2026-11-01).
  */
object NodeFault:

  /** A store's entry for `hash` as a node, keccak first. */
  def check(hash: ByteString, raw: RawNodeRead): Either[NodeFault, MptNode] = raw match
    case RawNodeRead.Absent      => Left(Absent)
    case RawNodeRead.Undecodable => Left(Undecodable)
    case RawNodeRead.Bytes(encoded) =>
      if !StateStorage.hashesTo(hash, encoded) then Left(BadKeccak)
      else
        try Right(MptTraversals.decodeNode(encoded))
        catch case NonFatal(_) => Left(Undecodable)

  /** Whether the entry is provably not the node `hash` names: one the store cannot unwrap, or bytes that fail keccak.
    * Absent is not bad, and neither are bytes that pass keccak, decoded or not.
    */
  def provablyBad(hash: ByteString, raw: RawNodeRead): Boolean = raw match
    case RawNodeRead.Absent         => false
    case RawNodeRead.Undecodable    => true
    case RawNodeRead.Bytes(encoded) => !StateStorage.hashesTo(hash, encoded)
