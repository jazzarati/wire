# grpc-inspect

Confirms that gRPC errors (`grpc-status`, `grpc-message`) are delivered **only in HTTP/2 trailers**, not in initial response headers. Uses a minimal [Connect RPC](https://connectrpc.com/) server that always returns `FailedPrecondition`.

## Quick start

```bash
source ./bin/activate-hermit   # provides go, buf, grpcurl
buf generate                   # generate protobuf code
./inspect.sh                   # build, start server, run all tests, shut down
```

## What the script shows

The script hits the server three ways:

1. **Connect protocol** — `curl` with `application/json`. Returns HTTP 400 with a JSON error body.

2. **gRPC protocol (raw HTTP/2 curl)** — sends a minimal gRPC frame and prints raw headers/trailers. This is the clearest view — you can see `grpc-status` and `grpc-message` appear **only as trailers**:

   ```
   < HTTP/2 200
   < content-type: application/grpc
   <
   < grpc-message: sample error for input ""
   < grpc-status: 9
   ```

3. **gRPC protocol (grpcurl)** — same underlying behavior, but `grpcurl` consumes the trailers and reformats them into its own `ERROR:` block.

## Manual usage

```bash
source ./bin/activate-hermit
buf generate
go run .   # listens on 127.0.0.1:8895
```

Then in another terminal:

```bash
# Raw HTTP/2 — shows trailers directly
printf '\x00\x00\x00\x00\x00' | curl -sv --http2-prior-knowledge \
  -H "Content-Type: application/grpc" \
  -H "te: trailers" \
  --data-binary @- \
  http://127.0.0.1:8895/sample.v1.SampleService/Sample

# grpcurl
grpcurl -v -plaintext -d '{}' 127.0.0.1:8895 sample.v1.SampleService/Sample

# Connect (JSON)
curl -sv -H "Content-Type: application/json" -d '{}' \
  http://127.0.0.1:8895/sample.v1.SampleService/Sample
```
