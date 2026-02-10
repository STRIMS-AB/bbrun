# BBRun - HTTP API Testing DSL
# Install: brew install STRIMS-AB/tap/bbrun
# Or: brew tap STRIMS-AB/tap && brew install bbrun

class Bbrun < Formula
  desc "HTTP API testing DSL - write expressive API tests"
  homepage "https://github.com/STRIMS-AB/bbrun"
  version "0.1.18"
  license "MIT"

  on_macos do
    url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-macos-amd64-#{version}.tar.gz"
    sha256 "PLACEHOLDER"
  end

  on_linux do
    url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-linux-amd64-#{version}.tar.gz"
    sha256 "PLACEHOLDER"
  end

  def install
    bin.install "bbrun"
  end

  test do
    assert_match "bbrun", shell_output("#{bin}/bbrun --version")
  end
end
