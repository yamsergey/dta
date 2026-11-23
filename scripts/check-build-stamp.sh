#!/bin/zsh
# D-075: the build stamp (dta-version.properties, the daemon identity) is one per source state.
# Two builds of unchanged sources keep it (a CLI and a plugin built one after the other from one commit run together);
# a change to product sources gives a newer one (a rebuilt CLI still replaces an older daemon).
# Never sets DTA_BUILD_TIME (that is CI's one stamp per run). Exit 0 = both hold.
set -u
cd ${0:A:h:h}
unset DTA_BUILD_TIME
props=dta-client/build/generated/version/dta-version.properties
stamp() { ./gradlew -q --console=plain :dta-client:versionResource >/dev/null || exit 2; grep '^build=' $props; }
probe=dta-protocol/src/main/kotlin/io/yamsergey/dta/protocol/BuildStampProbe.kt
trap 'rm -f -- $probe' EXIT
rm -f -- $probe
a=$(stamp); sleep 2; b=$(stamp)
[[ $a == $b ]] || { echo "FAIL unchanged sources restamped: $a then $b"; exit 1; }
echo "// D-075 probe: a source change $(date +%s%N)" > $probe; sleep 1
c=$(stamp)
[[ $c > $b ]] || { echo "FAIL a source change kept the stamp: $b then $c"; exit 1; }
rm -f -- $probe
echo "PASS one stamp per source state ($a; after a change $c)"
