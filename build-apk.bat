@echo off
set JAVA_HOME=D:\jdk-17.0.12+7
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d D:\git\mobile-agent
call gradlew.bat assembleDebug
