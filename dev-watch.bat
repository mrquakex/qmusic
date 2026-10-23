@echo off
rem Rebuilds, reinstalls and reopens QMusic on the connected phone whenever a source file changes.
rem   dev-watch.bat           -> debug build (fast to build)
rem   dev-watch.bat release   -> optimised build (what actually feels smooth)
if /I "%1"=="release" (
  call gradlew.bat :androidApp:launchRelease --continuous
) else (
  call gradlew.bat :androidApp:launchDebug --continuous
)
