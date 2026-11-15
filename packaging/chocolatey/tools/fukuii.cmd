@echo off
rem fukuii launcher installed by Chocolatey. fukuii needs Java 25; bin\fukuii.bat takes JAVA_HOME first, then the
rem java on PATH, and an older JAVA_HOME makes the JVM refuse fukuiis options. So pick a Java 25 here and pass it
rem with the launchers own -java-home: FUKUII_JAVA_HOME if set, else JAVA_HOME when it is Java 25 or newer, else the
rem newest Temurin 25 under Program Files (the temurin25jre dependency), else leave the choice to fukuii.bat.
setlocal
set "_FUKUII_JH=%FUKUII_JAVA_HOME%"
if not defined _FUKUII_JH if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /r /c:"version \"2[5-9][.\"]" /c:"version \"[3-9][0-9][.\"]" >nul && set "_FUKUII_JH=%JAVA_HOME%"
)
if not defined _FUKUII_JH for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jre-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*") do set "_FUKUII_JH=%%~D"
if defined _FUKUII_JH (
  call "%~dp0fukuii-__VERSION__\bin\fukuii.bat" -java-home "%_FUKUII_JH%" %*
) else (
  call "%~dp0fukuii-__VERSION__\bin\fukuii.bat" %*
)
exit /b %ERRORLEVEL%
