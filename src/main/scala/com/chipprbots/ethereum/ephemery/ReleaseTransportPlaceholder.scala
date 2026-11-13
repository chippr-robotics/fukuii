package com.chipprbots.ethereum.ephemery

/** The network seam of the startup resolver. Slice S3 never fetches, so this is a placeholder that refuses; slice S4b
  * replaces it with the https transport of S4a and deletes this file.
  */
trait ReleaseTransport

object ReleaseTransport:

  /** The transport of a build that cannot fetch: it is never asked, because the resolver does not fetch yet. */
  object Refusing extends ReleaseTransport:
    override def toString: String = "ReleaseTransport.Refusing"
