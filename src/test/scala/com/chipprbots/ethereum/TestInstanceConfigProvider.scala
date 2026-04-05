package com.chipprbots.ethereum

import com.chipprbots.ethereum.utils.{Config, InstanceConfig, InstanceConfigProvider}

/** Default InstanceConfigProvider for test fixtures.
  * Provides the singleton Config as the instanceConfig,
  * making all cake pattern traits work in tests without changes.
  */
trait TestInstanceConfigProvider extends InstanceConfigProvider {
  // Use lazy val to avoid initialization order issues (PruningConfigBuilder.$init$ runs early).
  // StdNode uses val, so if mixed with StdNode, StdNode's val takes precedence.
  override lazy val instanceConfig: InstanceConfig = Config
}
