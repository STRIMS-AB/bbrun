# BBRun - HTTP API Testing DSL
# Install: brew install STRIMS-AB/tap/bbrun
# Or: brew tap STRIMS-AB/tap && brew install bbrun

class Bbrun < Formula
  desc "HTTP API testing DSL - write expressive API tests"
  homepage "https://github.com/STRIMS-AB/bbrun"
  version "0.1.0"
  license "MIT"

  # Native binaries (no JVM required)
  on_macos do
    on_arm do
      url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}-macos-aarch64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_ARM64"
    end
    on_intel do
      url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}-macos-x64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_X64"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}-linux-aarch64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_LINUX_ARM64"
    end
    on_intel do
      url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}-linux-x64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_LINUX_X64"
    end
  end

  def install
    bin.install "bbrun"
  end

  test do
    # Simple test to verify installation
    assert_match "BBRun", shell_output("#{bin}/bbrun --version")
  end
end
