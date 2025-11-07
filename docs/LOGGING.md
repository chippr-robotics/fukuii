# Fukuii Logging Configuration

## Overview

Fukuii uses SLF4J with Logback for logging. The logging configuration can be controlled through the application configuration files.

## Configuration Files

### Main Configuration Files

- `src/main/resources/conf/base.conf` - Base configuration with logging settings
- `src/main/resources/conf/app.conf` - Application configuration that includes base.conf
- `src/main/resources/logback.xml` - Logback XML configuration

### Logging Settings in base.conf

```hocon
logging {
  # Flag used to switch logs to the JSON format
  json-output = false

  # Logs directory
  logs-dir = ${fukuii.datadir}"/logs"

  # Logs filename
  logs-file = "fukuii"

  # Logs level (DEBUG, INFO, WARN, ERROR)
  logs-level = "INFO"
}

pekko {
  loggers = ["org.apache.pekko.event.slf4j.Slf4jLogger"]
  
  # Pekko's log level (must be at or below logback's level)
  loglevel = "INFO"
  loglevel = ${?PEKKO_LOGLEVEL}  # Can be overridden by environment variable
}
```

## Changing Log Levels

### 1. Via Configuration File

Edit `src/main/resources/conf/base.conf` (or your custom config file):

```hocon
logging {
  logs-level = "DEBUG"  # or INFO, WARN, ERROR
}
```

### 2. Via Environment Variable (Pekko only)

```bash
export PEKKO_LOGLEVEL=DEBUG
./target/universal/stage/bin/fukuii
```

Note: This only affects Pekko's log level. The overall logback level is still controlled by `logging.logs-level`.

## Log Output Formats

### Standard Format (Default)

Human-readable format suitable for development and debugging:
```
2025-11-07 04:02:59 INFO  [com.chipprbots.ethereum.Fukuii] - Starting Fukuii...
```

### JSON Format

Structured JSON format suitable for log aggregation and analysis:

```hocon
logging {
  json-output = true
}
```

Output:
```json
{"timestamp":"2025-11-07T04:02:59.123Z","level":"INFO","logger":"com.chipprbots.ethereum.Fukuii","message":"Starting Fukuii...","node":"fukuii-node-1"}
```

## Log Levels Explained

- **DEBUG**: Detailed diagnostic information for troubleshooting
- **INFO**: General informational messages about application progress
- **WARN**: Warning messages about potentially harmful situations
- **ERROR**: Error messages about failures that allow the application to continue

## Default Log Level

The default log level is **INFO**, which provides a good balance between visibility and performance. This prevents excessive log output while still capturing important operational information.

## Troubleshooting

### Logs are too verbose

If you're seeing too many log messages:

1. Check that `logging.logs-level` in base.conf is set to "INFO" (not "DEBUG")
2. Verify that `PEKKO_LOGLEVEL` environment variable is not set to "DEBUG"
3. Review the logback.xml file for any logger-specific overrides

### Logs show wrong level

If logs appear to ignore the configuration:

1. Ensure your config file is being loaded (check `-Dconfig.file` parameter)
2. Verify that logback.xml is in the conf directory
3. Check the application startup logs for any configuration warnings
4. Make sure you've rebuilt the application after changing configuration files

### Configuration is not loaded

The LoadFromApplicationConfiguration class loads properties from TypeSafe Config into Logback. If it fails to load a property, it will:

1. Use a sensible default value
2. Log a warning message
3. Continue startup without failing

Default values:
- `logging.logs-level` → "INFO"
- `logging.json-output` → "false"
- `logging.logs-dir` → "./logs"
- `logging.logs-file` → "fukuii"

## Best Practices

1. **Production**: Use INFO or WARN level for production environments
2. **Development**: INFO is usually sufficient, use DEBUG sparingly
3. **Troubleshooting**: Enable DEBUG temporarily for specific loggers in logback.xml
4. **JSON Output**: Enable for centralized logging systems (ELK, Splunk, etc.)
5. **Log Rotation**: Logback is configured to rotate logs at 10MB per file, keeping up to 50 archived files

## Example: Debugging Specific Components

To enable DEBUG for specific components without flooding all logs, edit `logback.xml`:

```xml
<!-- Add before the closing </configuration> tag -->
<logger name="com.chipprbots.ethereum.blockchain.sync" level="DEBUG" />
```

Then restart the application. Remember to change it back to INFO when done debugging.
