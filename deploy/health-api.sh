#!/bin/bash
set -eu
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
read -r status <&3
[[ "$status" == *" 200 "* ]]
