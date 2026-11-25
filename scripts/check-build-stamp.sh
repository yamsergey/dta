#!/bin/zsh
# D-075: the build stamp (dta-version.properties, the daemon identity) is one per source state.
# D-096: the identity of a source state is `content=` (sha256 of the stamp inputs): two builds of one content are one
# build (a CLI and a plugin built one after the other run together), even when a file was touched and reverted between
# them; `build=` (the time) only orders two contents: a change to product sources gives a newer one (a rebuilt CLI still
# replaces an older daemon).
# Never sets DTA_BUILD_TIME (that is CI's one stamp per run). Exit 0 = both hold.
set -u
cd ${0:A:h:h}
unset DTA_BUILD_TIME
props=dta-client/build/generated/version/dta-version.properties
# The identity: content= (a build without it, before D-096, is checked by its build= as it was identified then).
id() { grep '^content=' $props || grep '^build=' $props; }
stamp() { ./gradlew -q --console=plain :dta-client:versionResource >/dev/null || exit 2; id; }
btime() { grep '^build=' $props; }
probe=dta-protocol/src/main/kotlin/io/yamsergey/dta/protocol/BuildStampProbe.kt
trap 'rm -f -- $probe' EXIT
rm -f -- $probe
a=$(stamp); abuild=$(btime); sleep 2; b=$(stamp)
[[ $a == $b ]] || { echo "FAIL unchanged sources restamped: $a then $b"; exit 1; }
# Gate 9 (D-094): the stamp is a function of the source state, not of when the task last ran. A forced re-run
# (`--rerun-tasks`) and a lost output or task history (a Gradle run killed while versionResource ran: Gradle had already
# removed the old output, or never recorded the new one) re-execute the task; the stamp must stay.
sleep 2
./gradlew -q --console=plain :dta-client:versionResource --rerun-tasks >/dev/null || exit 2; r=$(id)
[[ $r == $a ]] || { echo "FAIL a forced re-run (--rerun-tasks) restamped unchanged sources: $a then $r"; exit 1; }
sleep 2
rm -rf -- dta-client/build/generated/version
k=$(stamp)
[[ $k == $a ]] || { echo "FAIL a lost output (interrupted build) restamped unchanged sources: $a then $k"; exit 1; }
# Gate 4 (D-075 reopened): what the robot IDE runs. The CLI, then the plugin's sandbox, from unchanged sources: one stamp,
# though the builds leave tool directories beside the modules (.kotlin/ appeared between the two and restamped the plugin).
tooldir=.d075-tool-dir-probe; trap 'rm -f -- $probe; rm -rf -- $tooldir' EXIT
./gradlew -q --console=plain :dta-cli:installDist >/dev/null 2>&1 || exit 2; cli=$(id)
mkdir -p $tooldir/sessions; echo x > $tooldir/sessions/s.salive
./gradlew -q --console=plain :dta-studio:prepareSandbox_runStudioForUiTests >/dev/null 2>&1 || exit 2; plugin=$(id)
rm -rf -- $tooldir
[[ $cli == $plugin ]] || { echo "FAIL the plugin's sandbox restamped the CLI's sources: $cli then $plugin"; exit 1; }
b=$plugin
# Gate 5 (D-075 reopened): the harness builds the CLI as an included build from its own directory, inside another git
# repository. The stamp (its SHA included) must be the same whatever directory Gradle runs from.
sha=$(git rev-parse --short HEAD)
outer=$(mktemp -d "${TMPDIR:-/tmp}/d075-outer.XXXXXX"); trap 'rm -f -- $probe; rm -rf -- $tooldir $outer' EXIT
print -r -- "includeBuild(\"$PWD\") { name = \"dta-sources\" }" > $outer/settings.gradle.kts
git -C $outer init -q && git -C $outer -c user.name=probe -c user.email=probe@localhost commit -q --allow-empty -m outer
./gradlew -q --console=plain -p $outer :dta-sources:dta-cli:installDist >/dev/null 2>&1 || exit 2
fromOuter="$(id) $(grep '^sha=' $props) "
[[ $fromOuter == "$plugin sha=$sha " ]] || { echo "FAIL a build from another directory changed the stamp: $plugin sha=$sha then $fromOuter"; exit 1; }
# The sidekick's BUILD (without DTA_BUILD_TIME, the commit's time) is the same kind of stamp: these sources' commit.
./gradlew -q --console=plain -p $outer :dta-sources:dta-sidekick:generateReleaseBuildConfig >/dev/null 2>&1 || exit 2
want="\"$(git log -1 --format=%cI)\""
got=$(grep -o 'BUILD = .*;' dta-sidekick/build/generated/source/buildConfig/release/io/yamsergey/dta/sidekick/BuildConfig.java)
[[ $got == "BUILD = $want;" ]] || { echo "FAIL the sidekick built from another directory took that directory's commit time: want $want, $got"; exit 1; }
# Gate 10 (D-096): a touch-and-revert (a file added under src/main and removed; an existing source edited and put back,
# so its time and its directory's time change, its content does not) is the same source state: the same content.
edited=dta-protocol/src/main/kotlin/io/yamsergey/dta/protocol/ops/DaemonMessages.kt
bak=$(mktemp "${TMPDIR:-/tmp}/d096-bak.XXXXXX"); cp -p -- $edited $bak
trap 'rm -f -- $probe; rm -rf -- $tooldir $outer; [[ -s $bak ]] && cat -- $bak > $edited; rm -f -- $bak' EXIT
# A build in between (the plugin built while the file was there) makes Gradle run the task again after the revert;
# so does a lost output.
sleep 2
echo "// D-096 probe $(date +%s%N)" > $probe; sleep 1; stamp >/dev/null; rm -f -- $probe; sleep 1
t=$(stamp)
[[ $t == $a ]] || { echo "FAIL a file added and removed (a build in between) changed the identity: $a then $t"; exit 1; }
echo "// D-096 edit" >> $edited; sleep 1; stamp >/dev/null; cat -- $bak > $edited; sleep 1
cmp -s -- $bak $edited || { echo "FAIL (script) $edited not put back"; exit 2; }
t=$(stamp)
[[ $t == $a ]] || { echo "FAIL a source edited and put back (a build in between) changed the identity: $a then $t"; exit 1; }
rm -rf -- dta-client/build/generated/version
t=$(stamp)
[[ $t == $a ]] || { echo "FAIL after a touch-and-revert a lost output changed the identity: $a then $t"; exit 1; }
before=$(btime)
echo "// D-075 probe: a source change $(date +%s%N)" > $probe; sleep 1
c=$(stamp); cbuild=$(btime)
[[ $c != $a ]] || { echo "FAIL a source change kept the identity: $a"; exit 1; }
[[ $cbuild > $before ]] || { echo "FAIL a source change did not give a newer build time: $before then $cbuild"; exit 1; }
rm -f -- $probe
echo "PASS one identity per source state ($a, $abuild; touch-and-revert kept it; after a change $c, $cbuild)"
