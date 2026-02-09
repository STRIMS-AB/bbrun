#!/usr/bin/env bash
#
# BBRun Installer
# https://github.com/STRIMS-AB/bbrun
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/STRIMS-AB/bbrun/main/dist/install.sh | bash
#
# Environment variables:
#   BBRUN_VERSION   - Version to install (default: latest)
#   BBRUN_INSTALL   - Installation directory (default: ~/.local/bbrun)
#

set -euo pipefail

REPO="STRIMS-AB/bbrun"
VERSION="${BBRUN_VERSION:-latest}"
INSTALL_DIR="${BBRUN_INSTALL:-$HOME/.local/bbrun}"
BIN_DIR="$HOME/.local/bin"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() { echo -e "${GREEN}▸${NC} $1"; }
warn() { echo -e "${YELLOW}▸${NC} $1"; }
error() { echo -e "${RED}✗${NC} $1" >&2; exit 1; }

# Check for Java
check_java() {
    if ! command -v java &> /dev/null; then
        error "Java 17+ is required but not found. Please install Java first."
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
        error "Java 17+ is required. Found version $JAVA_VERSION"
    fi
    info "Found Java $JAVA_VERSION"
}

# Get latest version from GitHub
get_latest_version() {
    curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v([^"]+)".*/\1/'
}

main() {
    info "Installing BBRun..."

    # Check Java
    check_java

    # Resolve version
    if [ "$VERSION" = "latest" ]; then
        VERSION=$(get_latest_version)
        info "Latest version: $VERSION"
    fi

    # Download URL
    ARCHIVE="bbrun-${VERSION}.tar.gz"
    URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ARCHIVE}"

    # Create directories
    mkdir -p "$INSTALL_DIR" "$BIN_DIR"

    # Download and extract
    info "Downloading $URL"
    curl -fsSL "$URL" | tar -xz -C "$INSTALL_DIR" --strip-components=1

    # Make scripts executable
    chmod +x "$INSTALL_DIR/bin/"*

    # Create symlink
    ln -sf "$INSTALL_DIR/bin/bbrun-cli" "$BIN_DIR/bbrun"

    # Verify installation
    if [ -x "$BIN_DIR/bbrun" ]; then
        info "BBRun installed successfully!"
        "$BIN_DIR/bbrun" --version 2>/dev/null || true
    else
        warn "Installation complete, but bbrun not executable"
    fi

    # Check PATH
    if ! echo "$PATH" | grep -q "$BIN_DIR"; then
        echo ""
        warn "Add this to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
        echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        echo ""
    fi
}

main "$@"
