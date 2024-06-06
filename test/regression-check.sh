#!/bin/bash
set -euxo pipefail

# This script should be run before pushing to main - all errors must be addressed

script_dir="$(dirname "$(readlink -f "$0")")"
"$script_dir"/shellcheck.sh
"$script_dir"/test-shell.sh
"$script_dir"/test-example-apk.sh

# TODO
#"$script_dir"/test-shebang.sh

# Verify we can build clean
rm -r "$script_dir/../out"
"$script_dir/../build.sh"

set +x
echo
echo
echo All checks completed successfully
echo
echo
