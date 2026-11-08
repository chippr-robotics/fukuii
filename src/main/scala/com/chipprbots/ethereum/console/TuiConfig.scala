package com.chipprbots.ethereum.console

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException

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
  * @param glyphs
  *   `fukuii.tui.glyphs`, or `FUKUII_TUI_GLYPHS` once [[TuiEnv.applyTo]] has resolved it (never `Auto` then)
  * @param noColor
  *   draw with the monochrome theme (`NO_COLOR`, via [[TuiEnv]])
  */
case class TuiConfig(
    updateIntervalMs: Long = TuiConfig.DefaultUpdateIntervalMs,
    bannerDisplayDurationMs: Long = TuiConfig.DefaultBannerDisplayDurationMs,
    shutdownTimeoutMs: Long = TuiConfig.DefaultShutdownTimeoutMs,
    showLogo: Boolean = true,
    showProgressBar: Boolean = true,
    showNodeSettings: Boolean = true,
    suppressConsoleLogs: Boolean = true,
    glyphs: GlyphChoice = GlyphChoice.Auto,
    noColor: Boolean = false
):
  /** The glyph set to draw with. An `auto` that no environment has resolved draws emoji. */
  def glyphSet: GlyphSet = glyphs match
    case GlyphChoice.Ascii => GlyphSet.Ascii
    case _                 => GlyphSet.Emoji

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

  /** The settings in a `fukuii.tui` block (`conf/base/tui.conf`). An absent key keeps its default; an invalid value
    * fails with a [[ConfigException.BadValue]] that names the key.
    */
  def fromConfig(tui: TypesafeConfig): TuiConfig =
    val glyphs =
      if !tui.hasPath("glyphs") then GlyphChoice.Auto
      else
        val value = tui.getString("glyphs")
        GlyphChoice
          .parse(value)
          .getOrElse(
            throw new ConfigException.BadValue(
              tui.origin(),
              "fukuii.tui.glyphs",
              s"'$value' is not one of ${GlyphChoice.Accepted}"
            )
          )
    TuiConfig(glyphs = glyphs)

  /** The TUI configuration a node runs with: `fukuii.tui` from the node's config (the `fukuii` block), with the
    * environment's overrides applied. A config without the block, such as a full copy written before `tui.conf`
    * existed, gets the defaults.
    */
  def load(fukuii: TypesafeConfig, env: TuiEnv): TuiConfig =
    val tui = if fukuii.hasPath("tui") then fromConfig(fukuii.getConfig("tui")) else TuiConfig.default
    env.applyTo(tui)
