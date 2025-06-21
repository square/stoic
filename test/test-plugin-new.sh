#!/bin/bash
#set -x
set -euo pipefail

export STOIC_CONFIG="$(mktemp -d)"
echo "STOIC_CONFIG=$STOIC_CONFIG"
cd "$STOIC_CONFIG"

# Verify we can create and run a new plugin
stoic plugin --new a-new-plugin
stoic a-new-plugin
