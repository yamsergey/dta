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
# Gate 4 (D-075 reopened): what the robot IDE runs. The CLI, then the plugin's sandbox, from unchanged sources: one stamp,
# though the builds leave tool directories beside the modules (.kotlin/ appeared between the two and restamped the plugin).
tooldir=.d075-tool-dir-probe; trap 'rm -f -- $probe; rm -rf -- $tooldir' EXIT
./gradlew -q --console=plain :dta-cli:installDist >/dev/null 2>&1 || exit 2; cli=$(grep '^build=' $props)
mkdir -p $tooldir/sessions; echo x > $tooldir/sessions/s.salive
./gradlew -q --console=plain :dta-studio:prepareSandbox_runStudioForUiTests >/dev/null 2>&1 || exit 2; plugin=$(grep '^build=' $props)
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
fromOuter=$(grep -E '^(build|sha)=' $props | tr '\n' ' ')
[[ $fromOuter == "$plugin sha=$sha " ]] || { echo "FAIL a build from another directory changed the stamp: $plugin sha=$sha then $fromOuter"; exit 1; }
# The sidekick's BUILD (without DTA_BUILD_TIME, the commit's time) is the same kind of stamp: these sources' commit.
./gradlew -q --console=plain -p $outer :dta-sources:dta-sidekick:generateReleaseBuildConfig >/dev/null 2>&1 || exit 2
want="\"$(git log -1 --format=%cI)\""
got=$(grep -o 'BUILD = .*;' dta-sidekick/build/generated/source/buildConfig/release/io/yamsergey/dta/sidekick/BuildConfig.java)
[[ $got == "BUILD = $want;" ]] || { echo "FAIL the sidekick built from another directory took that directory's commit time: want $want, $got"; exit 1; }
echo "// D-075 probe: a source change $(date +%s%N)" > $probe; sleep 1
c=$(stamp)
[[ $c > $b ]] || { echo "FAIL a source change kept the stamp: $b then $c"; exit 1; }
rm -f -- $probe
echo "PASS one stamp per source state ($a; after a change $c)"
