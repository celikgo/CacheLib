# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Multi-stage Dockerfile for CacheLib gRPC Server
# Stage 1: Build environment
FROM ubuntu:24.04 AS builder

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Install base dependencies for getdeps.py and gRPC
RUN apt-get update && apt-get install -y \
    autoconf \
    automake \
    binutils-dev \
    bison \
    build-essential \
    cmake \
    curl \
    flex \
    git \
    libboost-all-dev \
    libdouble-conversion-dev \
    libevent-dev \
    libgflags-dev \
    libgmock-dev \
    libgoogle-glog-dev \
    libgtest-dev \
    libiberty-dev \
    libjemalloc-dev \
    liblz4-dev \
    liblzma-dev \
    libnuma-dev \
    libsnappy-dev \
    libsodium-dev \
    libssl-dev \
    libtool \
    libunwind-dev \
    libzstd-dev \
    libaio-dev \
    libfmt-dev \
    ninja-build \
    pkg-config \
    python3 \
    python3-pip \
    wget \
    zlib1g-dev \
    sudo \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /build

# Install gRPC and protobuf from source for consistent versions
ARG GRPC_VERSION=v1.60.0
RUN git clone --recurse-submodules -b ${GRPC_VERSION} --depth 1 https://github.com/grpc/grpc && \
    cd grpc && \
    mkdir -p cmake/build && \
    cd cmake/build && \
    cmake -DgRPC_INSTALL=ON \
          -DgRPC_BUILD_TESTS=OFF \
          -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_INSTALL_PREFIX=/usr/local \
          -G Ninja \
          ../.. && \
    ninja -j$(nproc) && \
    ninja install && \
    cd /build && \
    rm -rf grpc

# NOTE: NOT installing liburing - folly's io_uring support requires
# kernel features not available in Docker VM. By not having liburing,
# folly will build without io_uring support.

# Clone CacheLib from GitHub (ensures all dependencies are properly fetched)
ARG CACHELIB_REPO=https://github.com/facebook/CacheLib.git
ARG CACHELIB_BRANCH=main
RUN git clone --depth 1 -b ${CACHELIB_BRANCH} ${CACHELIB_REPO} CacheLib

# Copy the standalone_server from local context (our new code)
COPY standalone_server /build/CacheLib/standalone_server

# Patch folly manifest to disable io_uring (Docker VM kernel limitation)
COPY build/fbcode_builder/manifests/folly /build/CacheLib/build/fbcode_builder/manifests/folly

# Build CacheLib using getdeps.py (recommended approach)
WORKDIR /build/CacheLib
RUN python3 ./build/fbcode_builder/getdeps.py --allow-system-packages build cachelib

# Find the CacheLib install directory and all dependency directories
RUN CACHELIB_INSTALL=$(python3 ./build/fbcode_builder/getdeps.py --allow-system-packages show-inst-dir cachelib) && \
    INSTALL_PREFIX=$(dirname ${CACHELIB_INSTALL}) && \
    echo "CACHELIB_INSTALL=${CACHELIB_INSTALL}" && \
    echo "INSTALL_PREFIX=${INSTALL_PREFIX}" && \
    ln -s ${CACHELIB_INSTALL} /opt/cachelib-install && \
    ln -s ${INSTALL_PREFIX} /opt/getdeps-install

# Build the gRPC server
WORKDIR /build/CacheLib/standalone_server
RUN mkdir -p build && \
    cd build && \
    CACHELIB_DIR=$(readlink -f /opt/cachelib-install) && \
    INSTALL_PREFIX=$(readlink -f /opt/getdeps-install) && \
    # Build CMAKE_PREFIX_PATH from all installed dependencies
    CMAKE_PATHS="${CACHELIB_DIR}" && \
    for dir in ${INSTALL_PREFIX}/*/; do CMAKE_PATHS="${CMAKE_PATHS};${dir}"; done && \
    CMAKE_PATHS="${CMAKE_PATHS};/usr/local" && \
    echo "CMAKE_PREFIX_PATH=${CMAKE_PATHS}" && \
    cmake -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_PREFIX_PATH="${CMAKE_PATHS}" \
          -DBUILD_TESTS=OFF \
          -G Ninja \
          .. && \
    ninja -j$(nproc)

# Stage 2: Runtime image
FROM ubuntu:24.04 AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# Install runtime dependencies only (Ubuntu 24.04 packages)
RUN apt-get update && apt-get install -y \
    libboost-context1.83.0 \
    libboost-filesystem1.83.0 \
    libboost-program-options1.83.0 \
    libboost-regex1.83.0 \
    libboost-system1.83.0 \
    libboost-thread1.83.0 \
    libdouble-conversion3 \
    libevent-2.1-7t64 \
    libgflags2.2 \
    libgoogle-glog0v6t64 \
    libjemalloc2 \
    liblz4-1 \
    libnuma1 \
    libsnappy1v5 \
    libsodium23 \
    libssl3t64 \
    libunwind8 \
    libzstd1 \
    liburing2 \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user for security
RUN useradd -m -s /bin/bash cachelib

# Copy built artifacts from builder stage
COPY --from=builder /opt/cachelib-install/lib /opt/cachelib/lib
COPY --from=builder /build/CacheLib/standalone_server/build/cachelib-grpc-server /usr/local/bin/
COPY --from=builder /usr/local/lib/libgrpc*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libprotobuf*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libabsl*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libre2*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libupb*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libaddress_sorting*.so* /usr/local/lib/
COPY --from=builder /usr/local/lib/libgpr*.so* /usr/local/lib/

# Copy proto file for reference
COPY --from=builder /build/CacheLib/standalone_server/proto/cache.proto /opt/cachelib/proto/

# Update library cache
RUN ldconfig

# Create directories for NVM cache (flash storage)
RUN mkdir -p /data/nvm && chown cachelib:cachelib /data/nvm

# Switch to non-root user
USER cachelib

# Set library path
ENV LD_LIBRARY_PATH="/opt/cachelib/lib:/usr/local/lib"

# Expose gRPC port
EXPOSE 50051

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD grpc_health_probe -addr=:50051 || exit 1

# Default command with sensible defaults
ENTRYPOINT ["/usr/local/bin/cachelib-grpc-server"]
CMD ["--address=0.0.0.0", "--port=50051", "--cache_size=1073741824"]
