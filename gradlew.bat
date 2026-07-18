@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle is not installed. Install Gradle 9.4.1, or open this project in Android Studio.
  exit /b 1
)
gradle %*
