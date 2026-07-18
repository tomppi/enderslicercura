#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
printf '%s\n' 'Gradle is not installed. Install Gradle 9.4.1, or open this project in Android Studio.' >&2
exit 1
