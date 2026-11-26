#!/bin/bash
# Builds the static site + JSON API.
# process-classes runs the Fusion CliLauncher (exec-maven-plugin) with the `site` @Command,
# which generates target/pages in-process, so the JVM exits when the container closes.

set -euo pipefail

mvn clean process-classes -Dcommand=site

# Verify the key outputs were produced (the CLI exits 0 even if a geocoder lookup
# silently degrades, so assert on the generated files instead).
if [ ! -s target/pages/index.html ] || [ ! -s target/pages/conferences.json ] \
   || [ ! -s target/pages/api/conferences/latest.json ] \
   || [ ! -s target/pages/api/index.html ] || [ ! -s target/pages/api/openapi.js ]; then
  echo "Generation failed: target/pages was not fully written (see the output above)." >&2
  exit 1
fi

echo "Build OK: target/pages is ready to deploy."