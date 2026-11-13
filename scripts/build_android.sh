#!/bin/sh
set -eu
flavor=${1:-pleos}
kind=${2:-release}
case "$flavor" in pleos) task_flavor=Pleos;; demo) task_flavor=Demo;; *) echo 'flavor: pleos | demo' >&2; exit 2;; esac
case "$kind" in release) task_kind=Release;; debug) task_kind=Debug;; *) echo 'kind: release | debug' >&2; exit 2;; esac
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/../apps/android"
exec ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' ":app:test${task_flavor}${task_kind}UnitTest" ":app:assemble${task_flavor}${task_kind}"
