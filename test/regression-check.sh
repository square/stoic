#!/bin/bash
set -euxo pipefail

# This script should be run before pushing to main - all errors must be addressed

script_dir="$(dirname "$(readlink -f "$0")")"
"$script_dir"/shellcheck.sh
"$script_dir"/test-shell.sh

# TODO
#"$script_dir"/test-shebang.sh
