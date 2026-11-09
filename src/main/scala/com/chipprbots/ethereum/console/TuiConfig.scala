package com.chipprbots.ethereum.console

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException

/** Configuration for the TUI module.
  *
  * @param updateIntervalMs
  *   the frame pace while nothing on screen moves (Q1: 1 fps)
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
  * @param animationIntervalMs
  *   the frame pace while anything on screen moves (Q1: 4 fps), and the length of an animation frame
  * @param statusPollIntervalMs
  *   how often [[TuiProbeThread]] polls the node
  * @param reducedMotion
  *   nothing moves but the worm with progress (`fukuii.tui.reduced-motion`, `FUKUII_TUI_MOTION=reduced`, §4.5)
  * @param splashMs
  *   how long the startup splash shows; 0 for none (`FUKUII_TUI_NO_SPLASH`)
  */
case class TuiConfig(
    updateIntervalMs: Long = TuiConfig.DefaultUpdateIntervalMs,
    shutdownTimeoutMs: Long = TuiConfig.DefaultShutdownTimeoutMs,
    showLogo: Boolean = true,
    showProgressBar: Boolean = true,
    showNodeSettings: Boolean = true,
    suppressConsoleLogs: Boolean = true,
    glyphs: GlyphChoice = GlyphChoice.Auto,
    noColor: Boolean = false,
    animationIntervalMs: Long = TuiConfig.DefaultAnimationIntervalMs,
    statusPollIntervalMs: Long = TuiConfig.DefaultStatusPollIntervalMs,
    reducedMotion: Boolean = false,
    splashMs: Long = TuiConfig.DefaultSplashMs
):
  /** The glyph set to draw with. An `auto` that no environment has resolved draws emoji. */
  def glyphSet: GlyphSet = glyphs match
    case GlyphChoice.Ascii => GlyphSet.Ascii
    case _                 => GlyphSet.Emoji

  /** The motion the TUI starts with, and resumes after `p`. */
  def motion: Motion = if reducedMotion then Motion.Reduced else Motion.Full

  /** The frame pace: the animation interval while anything on screen moves, the update interval otherwise (Q1). */
  def paceMs(animated: Boolean): Long = if animated then animationIntervalMs else updateIntervalMs

  /** The splash's length in ticks: `splashMs / animationIntervalMs` frames of motion; under reduced motion one static
    * frame held for one 1 s pace (two ticks, §4.5); none when `splashMs` is 0.
    */
  def splashTicks: Long =
    if splashMs <= 0 then 0L
    else if reducedMotion then TuiConfig.ReducedSplashTicks
    else (splashMs / animationIntervalMs.max(1L)).max(1L)

object TuiConfig:
  /** Default update interval in milliseconds. */
  val DefaultUpdateIntervalMs: Long = 1000

  /** The frame pace while anything moves: 4 fps (Q1). */
  val DefaultAnimationIntervalMs: Long = 250

  /** How often the node is polled (research §4.4). */
  val DefaultStatusPollIntervalMs: Long = 1000

  /** The splash's length (research §7.5). */
  val DefaultSplashMs: Long = 3000

  /** The reduced-motion splash: drawn at the TUI's start, and by the loop's first frame, which then waits one pace. */
  private val ReducedSplashTicks: Long = 2

  /** Default shutdown timeout in milliseconds. */
  val DefaultShutdownTimeoutMs: Long = 1000

  /** Create a default TUI configuration. */
  def default: TuiConfig = TuiConfig()

  /** Create a minimal TUI configuration (no logo, faster updates). */
  def minimal: TuiConfig = TuiConfig(
    updateIntervalMs = 500,
    splashMs = 0,
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
    def millis(key: String, default: Long, min: Long): Long =
      if !tui.hasPath(key) then default
      else
        val value = tui.getLong(key)
        if value < min then
          throw new ConfigException.BadValue(tui.origin(), s"fukuii.tui.$key", s"$value is below the minimum, $min")
        value
    TuiConfig(
      glyphs = glyphs,
      animationIntervalMs = millis("animation-interval-ms", DefaultAnimationIntervalMs, 1),
      statusPollIntervalMs = millis("status-poll-interval-ms", DefaultStatusPollIntervalMs, 1),
      reducedMotion = tui.hasPath("reduced-motion") && tui.getBoolean("reduced-motion"),
      splashMs = millis("splash-ms", DefaultSplashMs, 0)
    )

  /** The TUI configuration a node runs with: `fukuii.tui` from the node's config (the `fukuii` block), with the
    * environment's overrides applied. A config without the block, such as a full copy written before `tui.conf`
    * existed, gets the defaults.
    */
  def load(fukuii: TypesafeConfig, env: TuiEnv): TuiConfig =
    val tui = if fukuii.hasPath("tui") then fromConfig(fukuii.getConfig("tui")) else TuiConfig.default
    env.applyTo(tui)
