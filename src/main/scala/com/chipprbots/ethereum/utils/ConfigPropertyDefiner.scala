package com.chipprbots.ethereum.utils

import scala.compiletime.uninitialized

import ch.qos.logback.core.PropertyDefinerBase
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory

/** PropertyDefiner for logback that loads values from TypeSafe Config.
  *
  * This class is compatible with logback 1.5.x and provides configuration properties from application.conf/base.conf to
  * logback.
  *
  * Usage in logback.xml:
  * {{{
  * <define name="LOGSLEVEL" class="com.chipprbots.ethereum.utils.ConfigPropertyDefiner">
  *   <key>logging.logs-level</key>
  *   <defaultValue>INFO</defaultValue>
  * </define>
  * }}}
  *
  * Properties loaded:
  *   - logging.logs-level (default: "INFO") → LOGSLEVEL
  *   - logging.json-output (default: "false") → ASJSON
  *   - logging.logs-dir (default: "./logs") → LOGSDIR
  *   - logging.logs-file (default: "fukuii") → LOGSFILENAME
  */
class ConfigPropertyDefiner extends PropertyDefinerBase:

  private var key: String = uninitialized
  private var defaultValue: String = uninitialized

  /** Set the configuration key to load (called by logback via reflection) */
  def setKey(key: String): Unit =
    this.key = key

  /** Set the default value to use if key is not found (called by logback via reflection) */
  def setDefaultValue(defaultValue: String): Unit =
    this.defaultValue = defaultValue

  /** Return the property value from config, or default if not found */
  override def getPropertyValue(): String =
    Option(key).filter(_.nonEmpty) match
      case None =>
        addError("ConfigPropertyDefiner: 'key' property must be set")
        Option(defaultValue).getOrElse("")
      case Some(configKey) =>
        try
          val value = ConfigPropertyDefiner.config.getString(configKey)
          addInfo(s"ConfigPropertyDefiner: Loaded '$configKey' = '$value'")
          value
        catch
          case _: ConfigException.Missing =>
            Option(defaultValue).filter(_.nonEmpty) match
              case Some(default) =>
                addWarn(s"ConfigPropertyDefiner: Key '$configKey' not found, using default: $default")
                default
              case None =>
                addError(s"ConfigPropertyDefiner: Key '$configKey' not found and no default value provided")
                ""
          case e: Exception =>
            addError(s"ConfigPropertyDefiner: Error loading key '$configKey': ${e.getMessage}")
            Option(defaultValue).getOrElse("")

object ConfigPropertyDefiner:

  /** The configuration as it is now. `ConfigFactory.load()` caches its result until `invalidateCaches()`, so this does
    * not reload on each PropertyDefiner instantiation; and a logger configured again after the system properties
    * changed (`LogbackReload`, the Ephemery resolver) sees them, which a value kept here for ever would not.
    */
  private def config: Config = ConfigFactory.load()
