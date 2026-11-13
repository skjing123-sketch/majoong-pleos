#!/bin/bash
set -euo pipefail
trial_here="$(cd "$(dirname "$0")" && pwd -P)"
exec /usr/bin/perl "$trial_here/galaxy_trial.pl" --mode guard "$@"
