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

# Detect OS and architecture
detect_platform() {
    local os arch

    case "$(uname -s)" in
        Linux*)  os="linux" ;;
        Darwin*) os="macos" ;;
        *)       error "Unsupported OS: $(uname -s)" ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)    arch="x64" ;;
        aarch64|arm64)   arch="aarch64" ;;
        *)               error "Unsupported architecture: $(uname -m)" ;;
    esac

    echo "${os}-${arch}"
}

# Get latest version from GitHub
get_latest_version() {
    curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
        | grep '"tag_name"' \
        | sed -E 's/.*"v([^"]+)".*/\1/'
}

main() {
    info "Installing BBRun..."

    # Resolve version
    if [ "$VERSION" = "latest" ]; then
        VERSION=$(get_latest_version)
        info "Latest version: $VERSION"
    fi

    # Detect platform
    PLATFORM=$(detect_platform)
    info "Platform: $PLATFORM"

    # Download URL
    ARCHIVE="bbrun-${VERSION}-${PLATFORM}.tar.gz"
    URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ARCHIVE}"

    # Create directories
    mkdir -p "$INSTALL_DIR" "$BIN_DIR"

    # Download and extract
    info "Downloading $URL"
    curl -fsSL "$URL" | tar -xz -C "$INSTALL_DIR"

    # Create symlink
    ln -sf "$INSTALL_DIR/bbrun" "$BIN_DIR/bbrun"
    chmod +x "$BIN_DIR/bbrun"

    # Verify installation
    if command -v bbrun &> /dev/null; then
        info "BBRun installed successfully!"
        bbrun --version
    else
        warn "Installation complete, but bbrun not in PATH"
        echo ""
        echo "Add this to your shell profile (~/.bashrc, ~/.zshrc, etc.):"
        echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
        echo ""
    fi
}

main "$@"
