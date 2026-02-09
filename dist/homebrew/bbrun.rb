# BBRun - HTTP API Testing DSL
# Install: brew install STRIMS-AB/tap/bbrun
# Or: brew tap STRIMS-AB/tap && brew install bbrun

class Bbrun < Formula
  desc "HTTP API testing DSL - write expressive API tests"
  homepage "https://github.com/STRIMS-AB/bbrun"
  version "0.1.0"
  license "MIT"

  url "https://github.com/STRIMS-AB/bbrun/releases/download/v#{version}/bbrun-#{version}.tar.gz"
  sha256 "d8cff34b0983cbcd34e8e9d0b0c249369c78ce2414612d85178892685da60bad"

  depends_on "openjdk@17"

  def install
    # The tarball extracts to bbrun-0.1.0/ which contains bin/ and lib/
    # Install everything to libexec, preserving the structure
    libexec.install Dir["*"]
    
    # Remove Windows batch files
    rm_f Dir[libexec/"bin/*.bat"]
    
    # Make the script executable
    chmod 0755, libexec/"bin/bbrun-cli"
    
    # Create wrapper that sets JAVA_HOME and calls the actual script
    (bin/"bbrun").write <<~EOS
      #!/bin/bash
      export JAVA_HOME="#{Formula["openjdk@17"].opt_prefix}"
      exec "#{libexec}/bin/bbrun-cli" "$@"
    EOS
    chmod 0755, bin/"bbrun"
  end

  test do
    assert_match "BBRun", shell_output("#{bin}/bbrun --version")
  end
end
