#!/bin/sh
# Official Gradle Start Up Script for UN*X (Gradle 8.6)
APP_HOME=$( cd "${0%/*}" && pwd -P ) || exit
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
    exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "ERROR: Missing $WRAPPER_JAR and no system 'gradle' binary in PATH." >&2
    echo "To restore the binary gradle-wrapper.jar on an SDK workstation, run: gradle wrapper --gradle-version 8.6" >&2
    exit 1
fi
