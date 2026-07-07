#!/usr/bin/env bash
set -euo pipefail
java -jar UncivHeadless.jar --config measurement-config.json "$@"
