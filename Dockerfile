# Multi-stage build for RoboGene CI environment
# This image contains all tools needed for build, test, and deployment

FROM node:22-bookworm AS base

# Set non-interactive mode
ENV DEBIAN_FRONTEND=noninteractive \
    NODE_ENV=production

# Install system dependencies needed by all tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    wget \
    git \
    ca-certificates \
    # Java dependencies (use openjdk-17 from Bookworm, Clojure will work with it)
    openjdk-17-jre-headless \
    # Clojure deps
    rlwrap \
    # Playwright/Chromium deps
    libgbm1 \
    libx11-6 \
    libxss1 \
    libxrandr2 \
    libpango-1.0-0 \
    libdbus-1-3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxkbcommon0 \
    libxkbcommon-x11-0 \
    libxi6 \
    libxinerama1 \
    libxext6 \
    libfontconfig1 \
    libfreetype6 \
    # Development tools
    build-essential \
    python3 \
    zip \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Set Java home
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=$JAVA_HOME/bin:$PATH

# Install Clojure CLI
RUN curl -fsSLO https://github.com/clojure/brew-install/releases/download/1.12.0.1491/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh

# Install global npm tools
RUN npm install -g \
    azure-functions-core-tools@4 \
    --unsafe-perm true

# Create app directory
WORKDIR /workspace

# Copy package files
COPY package*.json ./

# Install npm dependencies
RUN npm ci

# Install playwright (with chromium and dependencies)
RUN npx playwright install --with-deps chromium

# Verify all tools are available
RUN echo "=== Tool Verification ===" && \
    node --version && \
    npm --version && \
    java -version && \
    clojure -version && \
    func --version && \
    python3 --version && \
    npx playwright --version && \
    echo "=== All tools verified ==="

# Clean up npm cache to reduce image size
RUN npm cache clean --force

# Labels for registry
LABEL maintainer="RoboGene Team" \
      description="CI environment for RoboGene with Node, Java, Clojure, and Playwright" \
      version="1.0"
