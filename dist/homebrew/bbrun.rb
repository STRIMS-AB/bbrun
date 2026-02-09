# BBRun - HTTP API Testing DSL
# Install: brew install STRIMS-AB/tap/bbrun
# Or: brew tap STRIMS-AB/tap && brew install bbrun

class Bbrun < Formula
  desc "HTTP API testing DSL - write expressive API tests"
  homepage "https://github.com/STRIMS-AB/bbrun"
  version "0.1.7"
  license "MIT"

  url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}.tar.gz"
  sha256 "8d775bef196ed59d06de1e7dcb3b18470a5a37ca91d48d8699a55faf1760b919"

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
