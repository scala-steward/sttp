package sttp.client.httpclient.fs2

import java.io.InputStream
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2.Stream
import fs2.interop.reactivestreams._
import org.reactivestreams.FlowAdapters
import sttp.client.{FollowRedirectsBackend, SttpBackend, SttpBackendOptions}
import sttp.client.httpclient.{HttpClientAsyncBackend, HttpClientBackend, WebSocketHandler}
import sttp.client.impl.cats.implicits._
import sttp.client.testing.SttpBackendStub

import scala.util.{Success, Try}
import sttp.client.ws.WebSocketResponse

import scala.concurrent.ExecutionContext

class HttpClientFs2Backend[F[_]: ConcurrentEffect: ContextShift] private (
    client: HttpClient,
    blocker: Blocker,
    chunkSize: Int,
    closeClient: Boolean,
    customizeRequest: HttpRequest => HttpRequest
) extends HttpClientAsyncBackend[F, Stream[F, Byte]](client, implicitly, closeClient, customizeRequest) {

  override def openWebsocket[T, WS_RESULT](
      request: sttp.client.Request[T, Stream[F, Byte]],
      handler: WebSocketHandler[WS_RESULT]
  ): F[WebSocketResponse[WS_RESULT]] =
    super.openWebsocket(request, handler).guarantee(ContextShift[F].shift)

  override def streamToRequestBody(stream: Stream[F, Byte]): HttpRequest.BodyPublisher =
    BodyPublishers.fromPublisher(FlowAdapters.toFlowPublisher(stream.chunks.map(_.toByteBuffer).toUnicastPublisher()))

  override def responseBodyToStream(responseBody: InputStream): Try[Stream[F, Byte]] =
    Success(fs2.io.readInputStream(responseBody.pure[F], chunkSize, blocker))
}

object HttpClientFs2Backend {
  private val defaultChunkSize: Int = 4096

  private def apply[F[_]: ConcurrentEffect: ContextShift](
      client: HttpClient,
      blocker: Blocker,
      chunkSize: Int,
      closeClient: Boolean,
      customizeRequest: HttpRequest => HttpRequest
  ): SttpBackend[F, Stream[F, Byte], WebSocketHandler] =
    new FollowRedirectsBackend(new HttpClientFs2Backend(client, blocker, chunkSize, closeClient, customizeRequest))

  def apply[F[_]: ConcurrentEffect: ContextShift](
      blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.Implicits.global),
      chunkSize: Int = defaultChunkSize,
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeRequest: HttpRequest => HttpRequest = identity
  ): F[SttpBackend[F, Stream[F, Byte], WebSocketHandler]] =
    Sync[F].delay(
      HttpClientFs2Backend(
        HttpClientBackend.defaultClient(options),
        blocker,
        chunkSize,
        closeClient = true,
        customizeRequest
      )
    )

  def resource[F[_]: ConcurrentEffect: ContextShift](
      blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.Implicits.global),
      chunkSize: Int = defaultChunkSize,
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customizeRequest: HttpRequest => HttpRequest = identity
  ): Resource[F, SttpBackend[F, Stream[F, Byte], WebSocketHandler]] =
    Resource.make(apply(blocker, chunkSize, options, customizeRequest))(_.close())

  def usingClient[F[_]: ConcurrentEffect: ContextShift](
      client: HttpClient,
      blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.Implicits.global),
      chunkSize: Int = defaultChunkSize,
      customizeRequest: HttpRequest => HttpRequest = identity
  ): SttpBackend[F, Stream[F, Byte], WebSocketHandler] =
    HttpClientFs2Backend(client, blocker, chunkSize, closeClient = false, customizeRequest)

  /**
    * Create a stub backend for testing, which uses the [[F]] response wrapper, and supports `Stream[F, Byte]`
    * streaming.
    *
    * See [[SttpBackendStub]] for details on how to configure stub responses.
    */
  def stub[F[_]: Concurrent]: SttpBackendStub[F, Stream[F, Byte]] = SttpBackendStub(implicitly)
}