#!/bin/sh
# Prints the Homebrew formula of a release: dist/formula.sh <version> <checksums.txt>
#
# checksums.txt is what `sha256sum bbrun-<version>-*.tar.gz` printed for the four
# executables of the release (the release workflow makes it). The formula goes into the
# tap, STRIMS-AB/homebrew-tap, as bbrun.rb; `brew install STRIMS-AB/tap/bbrun` then
# installs the executable of the user's platform.
set -e
VERSION="$1"
SUMS="$2"
[ -n "$VERSION" ] && [ -f "$SUMS" ] || { echo "usage: dist/formula.sh <version> <checksums.txt>" >&2; exit 1; }

sum() {   # the checksum of one platform's archive
    found=$(awk -v f="bbrun-$VERSION-$1.tar.gz" '{ n = $2; sub(/^\*/, "", n); if (n == f) print $1 }' "$SUMS")
    [ -n "$found" ] || { echo "no checksum for bbrun-$VERSION-$1.tar.gz in $SUMS" >&2; exit 1; }
    echo "$found"
}
MACOS_ARM64=$(sum macos-arm64)
MACOS_X64=$(sum macos-x64)
LINUX_ARM64=$(sum linux-arm64)
LINUX_X64=$(sum linux-x64)
BASE="https://github.com/STRIMS-AB/bbrun/releases/download/v$VERSION/bbrun-$VERSION"

cat <<FORMULA
# bbrun: scripts that call services as black boxes. https://bbrun.org
# Written by dist/formula.sh of STRIMS-AB/bbrun for the release v$VERSION: do not edit.
class Bbrun < Formula
  desc "Scripting language for calling services: test scenarios and orchestration"
  homepage "https://bbrun.org"
  version "$VERSION"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "$BASE-macos-arm64.tar.gz"
      sha256 "$MACOS_ARM64"
    end
    on_intel do
      url "$BASE-macos-x64.tar.gz"
      sha256 "$MACOS_X64"
    end
  end

  on_linux do
    on_arm do
      url "$BASE-linux-arm64.tar.gz"
      sha256 "$LINUX_ARM64"
    end
    on_intel do
      url "$BASE-linux-x64.tar.gz"
      sha256 "$LINUX_X64"
    end
  end

  def install
    bin.install "bbrun"
  end

  test do
    assert_match "bbrun #{version}", shell_output("#{bin}/bbrun --version")
  end
end
FORMULA
