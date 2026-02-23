/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.squareup.wire.mockwebserver.GrpcDispatcher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Headers.Companion.headersOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import routeguide.Feature
import routeguide.Point
import routeguide.Rectangle
import routeguide.RouteGuideClient
import routeguide.RouteNote
import routeguide.RouteSummary

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class GrpcOnMockWebServerTest {
  @JvmField @Rule
  val mockWebServer = MockWebServer()

  @JvmField @Rule
  val timeout = Timeout(30, TimeUnit.SECONDS)

  private lateinit var okhttpClient: OkHttpClient
  private lateinit var grpcClient: GrpcClient
  private lateinit var routeGuideService: RouteGuideClient
  private var callReference = AtomicReference<Call>()
  private val fakeRouteGuide = FakeRouteGuide()

  /** This is a pass through interceptor that tests can replace without extra plumbing. */
  private var interceptor: Interceptor = object : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request())
  }

  @Before
  fun setUp() {
    mockWebServer.dispatcher = GrpcDispatcher(
      services = listOf(fakeRouteGuide),
      delegate = mockWebServer.dispatcher,
    )
    mockWebServer.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)

    okhttpClient = OkHttpClient.Builder()
      .addInterceptor { chain ->
        callReference.set(chain.call())
        interceptor.intercept(chain)
      }
      .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
      .build()
    grpcClient = GrpcClient.Builder()
      .client(okhttpClient)
      .baseUrl(mockWebServer.url("/"))
      .build()
    routeGuideService = grpcClient.create(RouteGuideClient::class)
  }

  @Test
  fun requestResponseSuspend() {
    runBlocking {
      val grpcCall = routeGuideService.GetFeature()
      val feature = grpcCall.execute(Point(latitude = 5, longitude = 6))

      assertThat(feature).isEqualTo(Feature(name = "tree"))
      assertThat(fakeRouteGuide.recordedGetFeatureCalls)
        .containsExactly(Point(latitude = 5, longitude = 6))
    }
  }

  /**
   * Integration test against a real Connect RPC server at localhost:8895.
   * Requires: cd /tmp/wire-test2/demo/grpc-inspect && source bin/activate-hermit && go run .
   *
   * Verified that OkHttp correctly surfaces HTTP/2 trailers even when the server sends
   * no DATA frames (trailers-only response). Wire produces a proper GrpcException with
   * grpc-status and grpc-message extracted from trailers.
   */
  @Test
  fun trailersOnlyErrorResponseFromRealServer() {
    val realClient = OkHttpClient.Builder()
      .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
      .build()
    val realGrpcClient = GrpcClient.Builder()
      .client(realClient)
      .baseUrl("http://127.0.0.1:8895")
      .build()

    val method = GrpcMethod<Point, Feature>(
      path = "/sample.v1.SampleService/Sample",
      requestAdapter = Point.ADAPTER,
      responseAdapter = Feature.ADAPTER,
    )

    val grpcCall = realGrpcClient.newCall(method)
    try {
      grpcCall.executeBlocking(Point())
      fail("Expected GrpcException")
    } catch (expected: GrpcException) {
      assertThat(expected.grpcStatus).isEqualTo(GrpcStatus.FAILED_PRECONDITION)
      assertThat(expected.grpcMessage).isEqualTo("sample error for input \"\"")
    }
  }

  /**
   * When a gRPC server returns an error with no response body (trailers-only response over real
   * HTTP/2), the wire library should extract grpc-status and grpc-message from the trailers.
   * This reproduces the behavior of Connect RPC servers that send FailedPrecondition errors:
   * the response has content-type: application/grpc, an empty body, and trailers containing
   * grpc-status and grpc-message.
   */
  @Test
  fun trailersOnlyErrorResponseOverHttp2() {
    // Use a separate MockWebServer without GrpcDispatcher so we can enqueue a raw response.
    val rawServer = MockWebServer()
    rawServer.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
    rawServer.start()

    try {
      rawServer.enqueue(
        MockResponse()
          .setHeader("Content-Type", "application/grpc")
          .setBody(okio.Buffer()) // empty body
          .setTrailers(
            headersOf(
              "grpc-status", "9",
              "grpc-message", "failed precondition",
            ),
          ),
      )

      val rawGrpcClient = GrpcClient.Builder()
        .client(okhttpClient)
        .baseUrl(rawServer.url("/"))
        .build()
      val rawRouteGuideService = rawGrpcClient.create(RouteGuideClient::class)

      val grpcCall = rawRouteGuideService.GetFeature()
      try {
        grpcCall.executeBlocking(Point(latitude = 5, longitude = 6))
        fail()
      } catch (expected: GrpcException) {
        assertThat(expected.grpcStatus).isEqualTo(GrpcStatus.FAILED_PRECONDITION)
        assertThat(expected.grpcMessage).isEqualTo("failed precondition")
      }
    } finally {
      rawServer.shutdown()
    }
  }

  class FakeRouteGuide : RouteGuideClient {
    val recordedGetFeatureCalls = mutableListOf<Point>()

    override fun GetFeature() = GrpcCall<Point, Feature> { request ->
      recordedGetFeatureCalls += request
      return@GrpcCall Feature(name = "tree")
    }

    override fun ListFeatures(): GrpcServerStreamingCall<Rectangle, Feature> {
      TODO("Not yet implemented")
    }

    override fun RecordRoute(): GrpcClientStreamingCall<Point, RouteSummary> {
      TODO("Not yet implemented")
    }

    override fun RouteChat(): GrpcStreamingCall<RouteNote, RouteNote> {
      TODO("Not yet implemented")
    }
  }
}
