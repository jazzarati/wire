#!/bin/bash
# Inspect Connect RPC vs gRPC protocol error responses.
#
# 1. Starts a local Connect RPC server that returns
#    FailedPrecondition on Sample.
# 2. Hits it with both Connect protocol (curl) and gRPC protocol (grpcurl)
#    to compare where grpc-status/grpc-message appear (headers vs trailers).
#
# Usage: ./inspect.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADDR="127.0.0.1:8895"

source "$SCRIPT_DIR/bin/activate-hermit"

echo "=== Building server ==="
(cd "$SCRIPT_DIR" && go build -o server .)

echo "=== Starting server on $ADDR ==="
"$SCRIPT_DIR/server" &
SERVER_PID=$!
trap "kill $SERVER_PID 2>/dev/null; rm -f $SCRIPT_DIR/server" EXIT
sleep 1

echo ""
echo "=============================================="
echo "1) Connect protocol"
echo "   POST with application/json, HTTP/1.1"
echo "=============================================="
curl -sv \
  -H "Content-Type: application/json" \
  -d '{}' \
  "http://$ADDR/sample.v1.SampleService/Sample" \
  2>&1

echo ""
echo ""
echo "=============================================="
echo "2) gRPC protocol (raw HTTP/2 via curl)"
echo "   Shows exact response headers and trailers"
echo "=============================================="
printf '\x00\x00\x00\x00\x00' | curl -sv --http2-prior-knowledge \
  -H "Content-Type: application/grpc" \
  -H "te: trailers" \
  --data-binary @- \
  "http://$ADDR/sample.v1.SampleService/Sample" \
  2>&1

echo ""
echo ""
echo "=============================================="
echo "3) gRPC protocol (grpcurl)"
echo "   grpcurl extracts grpc-status/grpc-message from trailers"
echo "   and displays them in the ERROR block, so trailers appear empty"
echo "=============================================="
grpcurl -v -plaintext \
  -d '{}' \
  "$ADDR" \
  sample.v1.SampleService/Sample \
  2>&1 || true
