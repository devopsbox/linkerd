package io.buoyant.linkerd
package protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.conversions.storage._
import com.twitter.finagle.{Path, Stack}
import com.twitter.finagle.Http.{param => hparam}
import com.twitter.finagle.buoyant.linkerd.{DelayedRelease, Headers, HttpTraceInitializer, HttpEngine}
import com.twitter.finagle.client.StackClient
import com.twitter.finagle.service.Retries
import io.buoyant.linkerd.protocol.http.{AccessLogger, ResponseClassifiers}
import io.buoyant.router.{Http, RoutingFactory}

class HttpInitializer extends ProtocolInitializer.Simple {
  val name = "http"

  protected type Req = com.twitter.finagle.http.Request
  protected type Rsp = com.twitter.finagle.http.Response

  protected val defaultRouter = {
    val pathStack = Http.router.pathStack
      .prepend(Headers.Dst.PathFilter.module)
      .replace(StackClient.Role.prepFactory, DelayedRelease.module)
      .prepend(http.ErrorResponder.module)
    val boundStack = Http.router.boundStack
      .prepend(Headers.Dst.BoundFilter.module)
    val clientStack = Http.router.clientStack
      .prepend(http.AccessLogger.module)
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.clientModule)
      .insertAfter(Retries.Role, http.StatusCodeStatsFilter.module)
      .insertAfter(StackClient.Role.prepConn, Headers.Ctx.clientModule)

    Http.router
      .withPathStack(pathStack)
      .withBoundStack(boundStack)
      .withClientStack(clientStack)
      .configured(RoutingFactory.DstPrefix(Path.Utf8(name)))
  }

  /**
   * Apply the router's codec configuration parameters to a server.
   */
  override protected def configureServer(router: Router, server: Server): Server =
    super.configureServer(router, server)
      .configured(router.params[hparam.MaxChunkSize])
      .configured(router.params[hparam.MaxHeaderSize])
      .configured(router.params[hparam.MaxInitialLineSize])
      .configured(router.params[hparam.MaxRequestSize])
      .configured(router.params[hparam.MaxResponseSize])
      .configured(router.params[hparam.Streaming])
      .configured(router.params[hparam.CompressionLevel])

  protected val defaultServer = {
    val stk = Http.server.stack
      .replace(HttpTraceInitializer.role, HttpTraceInitializer.serverModule)
      .prepend(Headers.Ctx.serverModule)
      .prepend(http.ErrorResponder.module)
      .prepend(http.StatusCodeStatsFilter.module)
    Http.server.withStack(stk)
  }

  val configClass = classOf[HttpConfig]

  override def defaultServerPort: Int = 4140
}

object HttpInitializer extends HttpInitializer

case class HttpClientConfig(
  engine: Option[HttpEngine]
) extends ClientConfig {
  override def clientParams = engine match {
    case Some(engine) => engine.mk(super.clientParams)
    case None => super.clientParams
  }
}

case class HttpServerConfig(
  engine: Option[HttpEngine]
) extends ServerConfig {
  override def serverParams = engine match {
    case Some(engine) => engine.mk(super.serverParams)
    case None => super.serverParams
  }
}

case class HttpConfig(
  httpAccessLog: Option[String],
  identifier: Option[HttpIdentifierConfig],
  maxChunkKB: Option[Int],
  maxHeadersKB: Option[Int],
  maxInitialLineKB: Option[Int],
  maxRequestKB: Option[Int],
  maxResponseKB: Option[Int],
  streamingEnabled: Option[Boolean],
  compressionLevel: Option[Int]
) extends RouterConfig {

  var client: Option[HttpClientConfig] = None
  var servers: Seq[HttpServerConfig] = Nil

  @JsonIgnore
  override def baseResponseClassifier =
    ResponseClassifiers.NonRetryableServerFailures orElse super.baseResponseClassifier

  @JsonIgnore
  override val protocol: ProtocolInitializer = HttpInitializer

  @JsonIgnore
  override def routerParams: Stack.Params = super.routerParams
    .maybeWith(httpAccessLog.map(AccessLogger.param.File(_)))
    .maybeWith(identifier.map(id => Http.param.HttpIdentifier(id.newIdentifier)))
    .maybeWith(maxChunkKB.map(kb => hparam.MaxChunkSize(kb.kilobytes)))
    .maybeWith(maxHeadersKB.map(kb => hparam.MaxHeaderSize(kb.kilobytes)))
    .maybeWith(maxInitialLineKB.map(kb => hparam.MaxInitialLineSize(kb.kilobytes)))
    .maybeWith(maxRequestKB.map(kb => hparam.MaxRequestSize(kb.kilobytes)))
    .maybeWith(maxResponseKB.map(kb => hparam.MaxResponseSize(kb.kilobytes)))
    .maybeWith(streamingEnabled.map(hparam.Streaming(_)))
    .maybeWith(compressionLevel.map(hparam.CompressionLevel(_)))

}
