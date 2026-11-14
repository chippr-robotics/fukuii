package com.chipprbots.ethereum.console

import ch.qos.logback.classic.Level
import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigException

/** The TUI's configuration as it runs: `fukuii.tui` ([[TuiSettings]]) with the environment applied
  * ([[TuiEnv.applyTo]]), plus the display switches the code sets.
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
  *   the glyph set to draw with: `fukuii.tui.glyphs` or `FUKUII_TUI_GLYPHS`, with `auto` already resolved against the
  *   locale. A [[GlyphSet]] has no `auto`, so nothing can draw an unresolved one (B-124)
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
  * @param logRingSize
  *   the log lines the Logs view keeps (`fukuii.tui.log-ring-size`, FR-021)
  * @param logLevel
  *   the lowest level the log ring keeps (`fukuii.tui.log-level`, Q4); the loggers' own levels apply first
  * @param readOnly
  *   no operator action can run (`fukuii.tui.read-only`, `FUKUII_TUI_READ_ONLY`; spec 029 FR-053). Stopping the node
  *   with `q` is an action
  */
final case class TuiConfig(
    updateIntervalMs: Long = TuiConfig.DefaultUpdateIntervalMs,
    shutdownTimeoutMs: Long = TuiConfig.DefaultShutdownTimeoutMs,
    showLogo: Boolean = true,
    showProgressBar: Boolean = true,
    showNodeSettings: Boolean = true,
    suppressConsoleLogs: Boolean = true,
    glyphs: GlyphSet = GlyphSet.Emoji,
    noColor: Boolean = false,
    animationIntervalMs: Long = TuiConfig.DefaultAnimationIntervalMs,
    statusPollIntervalMs: Long = TuiConfig.DefaultStatusPollIntervalMs,
    reducedMotion: Boolean = false,
    splashMs: Long = TuiConfig.DefaultSplashMs,
    logRingSize: Int = TuiConfig.DefaultLogRingSize,
    logLevel: Level = TuiConfig.DefaultLogLevel,
    readOnly: Boolean = false
):
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

  /** The log lines the ring keeps (FR-021, SC-009). */
  val DefaultLogRingSize: Int = 1000

  /** The ring keeps INFO and above (Q4). */
  val DefaultLogLevel: Level = Level.INFO

  /** The levels `fukuii.tui.log-level` accepts. */
  val LogLevels: Seq[Level] = Seq(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)

  /** The largest ring `fukuii.tui.log-ring-size` may ask for: a line is at most 512 characters (FR-020), so 10,000 are
    * at most 5 MiB of text, and a frame that reads the ring copies no more than that many references.
    */
  val MaxLogRingSize: Int = 10_000

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

  /** The TUI configuration a node runs with: `fukuii.tui` from the node's config (the `fukuii` block), with the
    * environment's overrides applied. A config without the block, such as a full copy written before `tui.conf`
    * existed, gets the defaults.
    */
  def load(fukuii: TypesafeConfig, env: TuiEnv): TuiConfig =
    val tui = if fukuii.hasPath("tui") then TuiSettings.fromConfig(fukuii.getConfig("tui")) else TuiSettings()
    env.applyTo(tui)

/** `fukuii.tui` as configured (`conf/base/tui.conf`), before the environment: the glyph choice may still be `auto`.
  * [[TuiEnv.applyTo]] resolves it into the [[TuiConfig]] the TUI runs with (B-124).
  */
final case class TuiSettings(
    glyphs: GlyphChoice = GlyphChoice.Auto,
    reducedMotion: Boolean = false,
    animationIntervalMs: Long = TuiConfig.DefaultAnimationIntervalMs,
    statusPollIntervalMs: Long = TuiConfig.DefaultStatusPollIntervalMs,
    splashMs: Long = TuiConfig.DefaultSplashMs,
    logRingSize: Int = TuiConfig.DefaultLogRingSize,
    logLevel: Level = TuiConfig.DefaultLogLevel,
    readOnly: Boolean = false
)

object TuiSettings:
  /** The settings in a `fukuii.tui` block (`conf/base/tui.conf`). An absent key keeps its default; an invalid value
    * fails with a [[ConfigException.BadValue]] that names the key.
    */
  def fromConfig(tui: TypesafeConfig): TuiSettings =
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
    val logLevel =
      if !tui.hasPath("log-level") then TuiConfig.DefaultLogLevel
      else
        val value = tui.getString("log-level")
        TuiConfig.LogLevels
          .find(_.toString.equalsIgnoreCase(value.trim))
          .getOrElse(
            throw new ConfigException.BadValue(
              tui.origin(),
              "fukuii.tui.log-level",
              s"'$value' is not one of ${TuiConfig.LogLevels.mkString(", ")}"
            )
          )
    val logRingSize =
      if !tui.hasPath("log-ring-size") then TuiConfig.DefaultLogRingSize
      else
        val value = tui.getInt("log-ring-size")
        if value < 1 || value > TuiConfig.MaxLogRingSize then
          throw new ConfigException.BadValue(
            tui.origin(),
            "fukuii.tui.log-ring-size",
            s"$value is not between 1 and ${TuiConfig.MaxLogRingSize}"
          )
        value
    TuiSettings(
      glyphs = glyphs,
      animationIntervalMs = millis("animation-interval-ms", TuiConfig.DefaultAnimationIntervalMs, 50),
      statusPollIntervalMs = millis("status-poll-interval-ms", TuiConfig.DefaultStatusPollIntervalMs, 100),
      reducedMotion = tui.hasPath("reduced-motion") && tui.getBoolean("reduced-motion"),
      splashMs = millis("splash-ms", TuiConfig.DefaultSplashMs, 0),
      logRingSize = logRingSize,
      logLevel = logLevel,
      readOnly = tui.hasPath("read-only") && tui.getBoolean("read-only")
    )
