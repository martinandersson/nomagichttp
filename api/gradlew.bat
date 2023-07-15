@echo off

:: Delegate the call to root wrapper
..\gradlew.bat -p %cd% %*
