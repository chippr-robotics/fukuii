package com.chipprbots.ethereum.utils

import ch.qos.logback.core.joran.action.Action
import ch.qos.logback.core.joran.spi.SaxEventInterpretationContext
import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import org.xml.sax.Attributes

/** Make properties defined in application.conf available to logback.
  * 
  * This class is instantiated by logback during initialization to load configuration
  * properties from the application's TypeSafe Config (application.conf/base.conf) and
  * make them available as logback variables.
  * 
  * Properties loaded:
  * - logging.logs-level (default: "INFO") → LOGSLEVEL
  * - logging.json-output (default: "false") → ASJSON  
  * - logging.logs-dir (default: "./logs") → LOGSDIR
  * - logging.logs-file (default: "fukuii") → LOGSFILENAME
  * 
  * If a property is missing from the configuration, a sensible default is used
  * and a warning is logged.
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
        val defaultValue = key match {
          case "logging.logs-level" => "INFO"
          case "logging.json-output" => "false"
          case "logging.logs-dir" => "./logs"
          case "logging.logs-file" => "fukuii"
          case _ => ""
        }
        if (defaultValue.nonEmpty) {
          ic.addSubstitutionProperty(asName, defaultValue)
          addWarn(s"Configuration key '$key' not found, using default value: $defaultValue")
        } else {
          addError(s"Configuration key '$key' not found and no default value available")
        }
    }
  }
  
  override def end(ic: SaxEventInterpretationContext, body: String): Unit = ()
}
