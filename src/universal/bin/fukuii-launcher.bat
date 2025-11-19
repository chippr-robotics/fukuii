@echo off
setlocal enabledelayedexpansion

cd "%~dp0\.."

set "CHAIN_PARAM="
set "JVM_ARGS="
set "APP_ARGS="
set "NETWORK_SPECIFIED=false"

:process_args
if "%1"=="" goto :check_defaults

REM Handle --config=<file> format
echo %1 | findstr /b /c:"--config=" >nul
if not errorlevel 1 (
    REM Extract value after --config= using substring
    set "FULL_ARG=%1"
    set "CONFIG_VALUE=!FULL_ARG:~9!"
    set "JVM_ARGS=!JVM_ARGS! -Dconfig.file=!CONFIG_VALUE!"
    shift
    goto :process_args
)

REM Handle --config <file> format
if "%1"=="--config" (
    if "%2"=="" (
        echo Error: --config requires a value
        echo Usage: fukuii --config ^<config-file^> [options]
        exit /b 1
    )
    set "JVM_ARGS=!JVM_ARGS! -Dconfig.file=%2"
    shift
    shift
    goto :process_args
)

REM Handle -D, -X, -XX, -agentlib flags (using exact prefix matching)
echo %1 | findstr /b /c:"-D" /c:"-X" /c:"-XX" /c:"-agentlib" >nul
if not errorlevel 1 (
    set "JVM_ARGS=!JVM_ARGS! %1"
    shift
    goto :process_args
)

REM Handle other flags (application arguments)
echo %1 | findstr /b /c:"-" >nul
if not errorlevel 1 (
    set "APP_ARGS=!APP_ARGS! %1"
    shift
    goto :process_args
)

REM Handle network name (first non-flag argument)
if "%NETWORK_SPECIFIED%"=="false" (
    set "CONFIG_FILE=conf\%1.conf"
    if exist "!CONFIG_FILE!" (
        set "CHAIN_PARAM=-Dconfig.file=!CONFIG_FILE!"
        set "NETWORK_SPECIFIED=true"
        shift
        goto :process_args
    ) else (
        echo Error: Unknown network '%1'
        echo.
        echo Usage: fukuii [network] [options]
        echo    or: fukuii [options]  ^(defaults to 'etc' network^)
        echo.
        echo Examples:
        echo   fukuii etc                      # Start Ethereum Classic node
        echo   fukuii mordor                   # Start Mordor testnet node
        echo   fukuii --config mining.conf     # Start with custom config
        exit /b 1
    )
) else (
    REM Already have a network, this is an app argument
    set "APP_ARGS=!APP_ARGS! %1"
    shift
    goto :process_args
)

:check_defaults
REM If no config was specified, use default
if "%CHAIN_PARAM%"=="" (
    REM Check if JVM_ARGS contains config.file
    set "HAS_CONFIG=false"
    if not "!JVM_ARGS!"=="" (
        echo !JVM_ARGS! | findstr /c:"-Dconfig.file=" >nul
        if not errorlevel 1 (
            set "HAS_CONFIG=true"
        )
    )
    if "!HAS_CONFIG!"=="false" (
        set "CHAIN_PARAM=-Dconfig.file=conf\etc.conf"
    )
)

:launch
call bin\fukuii.bat %CHAIN_PARAM% %JVM_ARGS% %APP_ARGS%
