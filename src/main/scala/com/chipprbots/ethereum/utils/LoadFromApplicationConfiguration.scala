package com.chipprbots.ethereum.utils

import ch.qos.logback.core.joran.action.Action
import ch.qos.logback.core.joran.spi.SaxEventInterpretationContext
import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import org.xml.sax.Attributes

/** Make properties defined in application.conf available to logback.
  * 
  * This class is instantiated by logback during initialization to load configuration
  * properties from the application's TypeSafe Config (application.conf/base.conf) and
  * make them available as logback substitution variables (used in logback.xml).
  * 
  * Properties loaded (config key → logback variable name):
  * - logging.logs-level (default: "INFO") → ${LOGSLEVEL} in logback.xml
  * - logging.json-output (default: "false") → ${ASJSON} in logback.xml
  * - logging.logs-dir (default: "./logs") → ${LOGSDIR} in logback.xml
  * - logging.logs-file (default: "fukuii") → ${LOGSFILENAME} in logback.xml
  * 
  * If a property is missing from the configuration, a sensible default is used
  * and a warning is logged. Unknown configuration keys will cause an error.
  */
class LoadFromApplicationConfiguration extends Action {

  val config: Config = ConfigFactory.load
  
  override def begin(ic: SaxEventInterpretationContext, body: String, attributes: Attributes): Unit = {
    val key = attributes.getValue("key")
    val asName = attributes.getValue("as")
    
    try {
      val value = config.getString(key)
      ic.addSubstitutionProperty(asName, value)
    } catch {
      case _: ConfigException.Missing =>
        // Provide sensible defaults for known properties
        val defaultValueOpt = key match {
          case "logging.logs-level" => Some("INFO")
          case "logging.json-output" => Some("false")
          case "logging.logs-dir" => Some("./logs")
          case "logging.logs-file" => Some("fukuii")
          case _ => None
        }
        defaultValueOpt match {
          case Some(defaultValue) =>
            ic.addSubstitutionProperty(asName, defaultValue)
            addWarn(s"Configuration key '$key' not found, using default value: $defaultValue")
          case None =>
            addError(s"Configuration key '$key' not found and no default value available")
        }
    }
  }
  
  override def end(ic: SaxEventInterpretationContext, body: String): Unit = ()
}
