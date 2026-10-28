#!/usr/bin/env bash
# Prints a Homebrew formula for the dta CLI tarball.
# Usage: homebrew-formula.sh <formula-name> <version> <tarball-url> <sha256> [channel-note]
set -euo pipefail

name="$1" version="$2" url="$3" sha="$4" note="${5:-}"
# dta-cli-dev -> DtaCliDev
class="$(echo "$name" | awk -F- '{ for (i = 1; i <= NF; i++) printf "%s", toupper(substr($i, 1, 1)) substr($i, 2) }')"

cat <<FORMULA
class ${class} < Formula
  desc "DTA: inspect a running Android app's screen and network from the terminal or an agent${note}"
  homepage "https://github.com/yamsergey/dta"
  url "${url}"
  sha256 "${sha}"
  license "Apache-2.0"
  version "${version}"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"dta").write_env_script libexec/"bin/dta", Language::Java.overridable_java_home_env("21")
  end

  test do
    assert_match "dta ${version}", shell_output("#{bin}/dta --version")
  end
end
FORMULA
