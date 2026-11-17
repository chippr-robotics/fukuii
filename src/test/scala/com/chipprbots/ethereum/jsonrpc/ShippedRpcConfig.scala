package com.chipprbots.ethereum.jsonrpc

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.ApisBuilder

/** The JSON-RPC configuration as it ships: `conf/base/network.conf` alone, before an operator sets anything. The test
  * classpath's own `application.conf` (which lists wide `rpc.apis` for the controller specs) is deliberately left out,
  * so a test built on this sees the defaults a fresh node gets.
  */
object ShippedRpcConfig:

  /** The namespaces the node can serve, as `ApisBuilder` declares them. */
  val available: List[String] = new ApisBuilder {}.available

  /** `conf/base/network.conf`, rooted at `fukuii` as the node includes it, with `overrides` (HOCON) on top. */
  def config(overrides: String = ""): Config =
    ConfigFactory
      .parseString(overrides)
      .withFallback(ConfigFactory.parseString("fukuii.datadir = \"/tmp/fukuii-shipped-config\""))
      .withFallback(ConfigFactory.parseResources("conf/base/network.conf").atPath("fukuii"))
      .resolve()

  /** The node's [[JsonRpcConfig]] over the shipped config plus `overrides`. */
  def jsonRpcConfig(overrides: String = ""): JsonRpcConfig =
    JsonRpcConfig(config(overrides).getConfig("fukuii"), available)
