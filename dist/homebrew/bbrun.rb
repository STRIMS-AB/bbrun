# BBRun - HTTP API Testing DSL
# Install: brew install STRIMS-AB/tap/bbrun
# Or: brew tap STRIMS-AB/tap && brew install bbrun

class Bbrun < Formula
  desc "HTTP API testing DSL - write expressive API tests"
  homepage "https://github.com/STRIMS-AB/bbrun"
  version "0.1.15"
  license "MIT"

  url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}.tar.gz"
  sha256 "c894593945a940131f038bb368d2f2f1570556472efc8cd68fb716fd8772c035"

  depends_on "openjdk@17"

  def install
    # Install everything to libexec
    libexec.install Dir["*"]
    
    # Make the script executable
    chmod 0755, libexec/"bin/bbrun"
    
    # Create wrapper that sets JAVA_HOME
    (bin/"bbrun").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}"
      exec "#{libexec}/bin/bbrun" "$@"
    EOS
    chmod 0755, bin/"bbrun"
  end

  test do
    assert_match "BBRun", shell_output("#{bin}/bbrun --version")
  end
end
