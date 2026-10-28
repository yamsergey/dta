#!/usr/bin/env bash
# Builds the dta CLI from this checkout and links it into a bin directory
# (default ~/.local/bin, or $DTA_BIN_DIR). Safe to run again.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bin_dir="${DTA_BIN_DIR:-$HOME/.local/bin}"

"$root/gradlew" -p "$root" :dta-cli:installDist -q

mkdir -p "$bin_dir"
ln -sf "$root/dta-cli/build/install/dta/bin/dta" "$bin_dir/dta"

echo "Linked $bin_dir/dta -> $root/dta-cli/build/install/dta/bin/dta"
case ":$PATH:" in
  *":$bin_dir:"*) ;;
  *) echo "Note: $bin_dir is not on PATH. Add it, for example: export PATH=\"$bin_dir:\$PATH\"" ;;
esac
"$bin_dir/dta" --version
