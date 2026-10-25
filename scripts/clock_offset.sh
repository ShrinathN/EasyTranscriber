#!/usr/bin/env bash
# Prints (phone wall clock - host wall clock) in ms, from the adb round trip with the smallest delay.
set -euo pipefail
best_rtt=999999; best_off=0
for i in $(seq 1 15); do
  h0=$(date +%s%N)
  p=$(adb shell date +%s%N | tr -d '\r')
  h1=$(date +%s%N)
  rtt=$(( (h1 - h0) / 1000000 ))
  off=$(( (p - (h0 + h1) / 2) / 1000000 ))
  if [ "$rtt" -lt "$best_rtt" ]; then best_rtt=$rtt; best_off=$off; fi
done
echo "$best_off"
echo "rtt_ms=$best_rtt (offset uncertainty about ±$((best_rtt / 2)) ms)" >&2
