#!/bin/sh
set -eu
test -f /tmp/worker-heartbeat
now=$(date +%s)
last=$(stat -c %Y /tmp/worker-heartbeat)
test $((now-last)) -le 20
