#!/bin/zsh
# Feature 068 D1: the one network feed under load on an emulator (quickstart.md, SC-001/002/004).
# The channel fixture app holds a socket open to server.py, which sends a small JSON message every
# WS_SLEEP seconds (0.0077 ≈ 130 messages/s). Samples every PAUSE s for MINUTES: archive lag (the
# daemon's own, from the app writing a record to archiving it), bodies waiting to be copied, the device history against its budget,
# the app's store and feed threads' CPU, dropped events. Then stops the daemon for GAP minutes of
# traffic and times the catch-up.
# Usage: scripts/feed-load/run.sh [MINUTES=5] [GAP=5]   (fixture installed, emulator-5554, CLI built)
set -u
HERE=${0:A:h}; ROOT=${HERE:h:h}
D=$ROOT/dta-cli/build/install/dta/bin/dta; X=io.yamsergey.dta.fixture.channel
AD=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}; SERIAL=${SERIAL:-emulator-5554}
MINUTES=${1:-5}; GAP=${2:-5}
# Seconds between samples: a CLI call is a query that holds the archive, so samples are kept apart.
PAUSE=${PAUSE:-30}
# The archive's own lag (feature 068: network.history, the most of the last 10 s), in ms.
feedlag() { echo "$1" | grep -oE 'archived at most [0-9]+ ms' | grep -oE '[0-9]+'; }
# The history first: its lag is the most of the last 10 s, which the pause before it left quiet.
sample() {
  local h=$($D -a $X network history 2>&1)
  local list=$($D -a $X network list --limit 1 2>&1)
  local P=$($AD -s $SERIAL shell pidof $X)
  echo "$(date +%T) feedlag=$(feedlag "$h")ms copying=$(echo "$list" | grep -o '[0-9]* bodies not copied' | grep -o '^[0-9]*' || echo 0) dropped=$(echo "$list" | grep -c dropped) $(echo "$h" | sed -n 2p | grep -o '[0-9.]* [MG]B' | head -1) cpu=$($AD -s $SERIAL shell top -H -b -n 1 -p $P | grep dta-network | awk '{printf "%s:%s ", $(NF-1), $9}')"
}
WS_SLEEP=${WS_SLEEP:-0.0077} python3 $HERE/server.py > /dev/null 2>&1 &
SERVER=$!
trap "kill $SERVER 2>/dev/null" EXIT
sleep 1
$AD -s $SERIAL reverse tcp:8098 tcp:8098 >/dev/null; $AD -s $SERIAL reverse tcp:8099 tcp:8099 >/dev/null
$AD -s $SERIAL shell am start -S -n $X/.MainActivity --es screen network --es action okWsOpenHold >/dev/null
echo "== load: $MINUTES min"
sleep 20
end=$(( $(date +%s) + MINUTES * 60 ))
while (( $(date +%s) < end )); do sleep $PAUSE; sample; done
echo "== daemon stopped for $GAP min of traffic"
$D daemon stop >/dev/null 2>&1
sleep $(( GAP * 60 ))
start=$(date +%s.%N)
$D -a $X network list --limit 1 >/dev/null 2>&1
limit=$(( $(date +%s) + 600 ))
while (( $(date +%s) < limit )); do
  h=$($D -a $X network history 2>&1)
  l=$(feedlag "$h")
  # Caught up: live again (the feed's lag is said only then) and under a second behind.
  if ! echo "$h" | grep -q "catching\|reading" && [[ -n "$l" ]] && (( l < 1000 )); then break; fi
  sleep 5
done
(( $(date +%s) < limit )) || echo "not caught up in 10 min"

printf "caught up in %.1fs\n" $(( $(date +%s.%N) - start ))
sample
