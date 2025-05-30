#!/bin/bash
set -euxo pipefail

# This script should be run before pushing to main - all errors must be addressed

script_dir="$(dirname "$(readlink -f "$0")")"

# Verify we can build clean
rm -r "$script_dir/../out"
"$script_dir/../build.sh"

"$script_dir"/shellcheck.sh
"$script_dir"/test-shell.sh
"$script_dir"/test-demo-app-without-sdk.sh
"$script_dir"/test-setup.sh

# TODO: these tests require functionality not available on older versions of
#   Android
# "$script_dir/../out/rel/bin/stoic" testsuite

# TODO
#"$script_dir"/test-shebang.sh


set +x
echo
echo
echo All checks completed successfully
echo
echo
