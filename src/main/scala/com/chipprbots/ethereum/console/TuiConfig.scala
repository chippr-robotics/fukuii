package com.chipprbots.ethereum.console

/** Configuration for the TUI module.
  *
  * @param updateIntervalMs
  *   Interval in milliseconds between UI updates
  * @param bannerDisplayDurationMs
  *   Duration in milliseconds to display startup banner
  * @param shutdownTimeoutMs
  *   Timeout in milliseconds for graceful shutdown
  * @param showLogo
  *   Whether to show the ASCII art logo
  * @param showProgressBar
  *   Whether to show sync progress bar
  * @param showNodeSettings
  *   Whether to show node settings section
  * @param suppressConsoleLogs
  *   Whether to suppress console logs while TUI is active
  */
case class TuiConfig(
    updateIntervalMs: Long = TuiConfig.DefaultUpdateIntervalMs,
    bannerDisplayDurationMs: Long = TuiConfig.DefaultBannerDisplayDurationMs,
    shutdownTimeoutMs: Long = TuiConfig.DefaultShutdownTimeoutMs,
    showLogo: Boolean = true,
    showProgressBar: Boolean = true,
    showNodeSettings: Boolean = true,
    suppressConsoleLogs: Boolean = true
)

object TuiConfig:
  /** Default update interval in milliseconds. */
  val DefaultUpdateIntervalMs: Long = 1000

  /** Default banner display duration in milliseconds. */
  val DefaultBannerDisplayDurationMs: Long = 1000

  /** Default shutdown timeout in milliseconds. */
  val DefaultShutdownTimeoutMs: Long = 1000

  /** Create a default TUI configuration. */
  def default: TuiConfig = TuiConfig()

  /** Create a minimal TUI configuration (no logo, faster updates). */
  def minimal: TuiConfig = TuiConfig(
    updateIntervalMs = 500,
    bannerDisplayDurationMs = 0,
    showLogo = false,
    showNodeSettings = false
  )
