#!/bin/bash
#set -x
set -euo pipefail
script_dir="$(realpath "$(dirname "$(readlink -f "$0")")")"

cd "$script_dir"/kotlin
./gradlew :internal:tool:prepare-release:run --args "$script_dir"

echo
echo
echo 'Next steps:'
echo '1. commit version change and push (git commit -m "version bump" && git push)'
echo '2. Upload /Users/tomm/Development/stoic/releases/stoic-0.0.2.tar.gz to Github Releases'
echo
