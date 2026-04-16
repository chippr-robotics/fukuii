#!/usr/bin/env bash
set -e

echo "🚀 Setting up Fukuii development environment..."

# Detect OS
if [ -f /etc/alpine-release ]; then
    OS="alpine"
    echo "📦 Detected Alpine Linux"
elif [ -f /etc/debian_version ]; then
    OS="debian"
    echo "📦 Detected Debian/Ubuntu"
else
    echo "❌ Unsupported OS"
    exit 1
fi

# Fix permissions for cache directories
echo "📁 Setting up cache directories..."
sudo mkdir -p $HOME/.sbt $HOME/.ivy2
sudo chown -R vscode:vscode $HOME/.sbt $HOME/.ivy2 || true

# Initialize git submodules
echo "📦 Initializing git submodules..."
git submodule update --init --recursive || true

# Install SBT
echo "⚙️  Installing SBT..."
if ! command -v sbt &> /dev/null; then
    if [ "$OS" = "alpine" ]; then
        # Install SBT on Alpine Linux
        echo "Installing SBT for Alpine Linux..."
        
        # Install dependencies
        sudo apk add --no-cache bash curl openjdk21 || true
        
        # Download and install SBT
        SBT_VERSION="1.10.7"
        cd /tmp
        curl -L "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o sbt.tgz
        sudo tar -xzf sbt.tgz -C /usr/local
        sudo ln -sf /usr/local/sbt/bin/sbt /usr/local/bin/sbt
        rm sbt.tgz
        cd -
        
    elif [ "$OS" = "debian" ]; then
        # Install SBT on Debian/Ubuntu
        sudo mkdir -p /etc/apt/sources.list.d /usr/share/keyrings
        echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list > /dev/null
        echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee /etc/apt/sources.list.d/sbt_old.list > /dev/null
        curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo gpg --dearmor > /tmp/sbt-archive-keyring.gpg
        sudo mv /tmp/sbt-archive-keyring.gpg /usr/share/keyrings/sbt-archive-keyring.gpg
        sudo apt-get update
        sudo apt-get install -y sbt
    fi
    
    echo "✅ SBT installed successfully"
else
    echo "✅ SBT already installed"
fi

# Verify installation
echo ""
echo "🔍 Verifying installation..."
echo "Java version:"
java -version
echo ""
echo "SBT version:"
sbt sbtVersion

echo ""
echo "✨ Development environment setup complete!"
echo "   You can now run: sbt compile"
