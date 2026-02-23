package main

import (
	"context"
	"fmt"
	"log"
	"net/http"

	"connectrpc.com/connect"
	"connectrpc.com/grpcreflect"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"

	samplev1 "grpc-inspect/gen/sample/v1"
	"grpc-inspect/gen/sample/v1/samplev1connect"
)

// server always returns FailedPrecondition.
type server struct{}

func (s *server) Sample(
	ctx context.Context,
	req *connect.Request[samplev1.SampleRequest],
) (*connect.Response[samplev1.SampleResponse], error) {
	return nil, connect.NewError(
		connect.CodeFailedPrecondition,
		fmt.Errorf("sample error for input %q", req.Msg.GetInput()),
	)
}

func main() {
	mux := http.NewServeMux()

	path, handler := samplev1connect.NewSampleServiceHandler(&server{})
	mux.Handle(path, handler)

	// Enable reflection so grpcurl can discover services
	reflector := grpcreflect.NewStaticReflector(samplev1connect.SampleServiceName)
	mux.Handle(grpcreflect.NewHandlerV1(reflector))
	mux.Handle(grpcreflect.NewHandlerV1Alpha(reflector))

	addr := "127.0.0.1:8895"
	log.Printf("Connect RPC server listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, h2c.NewHandler(mux, &http2.Server{})))
}
