package com.chipprbots.scalanet

import cats.effect.IO

package object peergroup {
  // IO that closes a PeerGroup or Channel.
  type Release = IO[Unit]
}
