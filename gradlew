#!/bin/sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVACMD="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
