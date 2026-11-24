#!/bin/sh
# Shared build hosts (e.g. Render) are often rate-limited by Maven Central (HTTP 429).
# Try Google's Central mirror first, then Central itself, with pauses, up to 3 rounds.
for attempt in 1 2 3; do
  mvn -s /tmp/maven-mirror.xml "$@" && exit 0
  echo "[mvn-retry] mirror attempt $attempt failed"; sleep 15
  mvn "$@" && exit 0
  echo "[mvn-retry] central attempt $attempt failed"; sleep 30
done
exit 1