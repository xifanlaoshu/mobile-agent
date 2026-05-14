#!/bin/sh
# Gradle wrapper for Unix/macOS

APP_HOME=$( cd "${0%[/\\]*}" > /dev/null && pwd )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

exec "$JAVA" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
