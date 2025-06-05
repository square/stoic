#!/bin/bash
#set -x
set -euo pipefail

export STOIC_CONFIG="$(mktemp -d)"
rmdir "$STOIC_CONFIG"

echo "STOIC_CONFIG=$STOIC_CONFIG"
stoic helloworld
stoic --restart helloworld
stoic tool shell echo hello shell
