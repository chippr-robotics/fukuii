package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory

/** The `state` section of the node configuration (`base/state.conf`).
  *
  * Read straight from the typesafe config, as [[StateReadCacheConfig]] is, so that the storage classes that ask do not
  * drag the whole node configuration into a unit test. A missing key is the default below.
  */
object StateLayoutConfig:

  /** `fukuii.state.path-live`: the in-development guard of spec 018 Track C. It selects the unified, position-keyed
    * state layout while the track's slices land, so that staging stays green between them. It is not an operator
    * option, and it is deleted with the Hash-scheme code it guards in the track's last slice.
    */
  lazy val pathLive: Boolean =
    val root = ConfigFactory.load()
    root.hasPath("fukuii.state.path-live") && root.getBoolean("fukuii.state.path-live")
