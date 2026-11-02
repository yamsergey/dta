#!/usr/bin/env bash
# Builds the Android Studio layout index of the IntelliJ Platform Gradle plugin before the real build.
#
# The plugin-structure library behind the IntelliJ Platform Gradle plugin sometimes fails to read a
# bundled plugin (ClosedFileSystemException, a race in its JAR file systems). The index is then written
# without that plugin (e.g. com.intellij.java), saved in .intellijPlatform/ides/layoutIndex, and every
# later resolution fails with "Could not find bundled plugin". So: resolve once, and if it failed,
# drop the index and try again.
set -uo pipefail
for attempt in 1 2 3; do
  if ./gradlew :dta-studio:test --dry-run --no-configuration-cache -q > /tmp/ide-index.log 2>&1; then
    echo "IDE layout index ready (attempt $attempt)"
    exit 0
  fi
  if ! grep -q "Could not find bundled plugin" /tmp/ide-index.log; then
    cat /tmp/ide-index.log
    exit 1
  fi
  echo "::warning::IDE layout index missed a bundled plugin (attempt $attempt); rebuilding it"
  rm -rf .intellijPlatform/ides/layoutIndex
done
cat /tmp/ide-index.log
exit 1
