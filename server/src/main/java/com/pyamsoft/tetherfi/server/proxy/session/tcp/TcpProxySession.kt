/*
 * Copyright 2024 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pyamsoft.tetherfi.server.proxy.session.tcp

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.ThreadEnforcer
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.core.Timber
import com.pyamsoft.tetherfi.server.IP_ADDRESS_REGEX
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.ServerPreferences
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.server.clients.AllowedClients
import com.pyamsoft.tetherfi.server.clients.BlockedClients
import com.pyamsoft.tetherfi.server.clients.ByteTransferReport
import com.pyamsoft.tetherfi.server.clients.ClientResolver
import com.pyamsoft.tetherfi.server.clients.TetherClient
import com.pyamsoft.tetherfi.server.event.ProxyRequest
import com.pyamsoft.tetherfi.server.proxy.ServerDispatcher
import com.pyamsoft.tetherfi.server.proxy.SocketTagger
import com.pyamsoft.tetherfi.server.proxy.SocketTracker
import com.pyamsoft.tetherfi.server.proxy.session.ProxySession
import com.pyamsoft.tetherfi.server.proxy.usingConnection
import com.pyamsoft.tetherfi.server.proxy.usingSocketBuilder
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketTimeoutException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
internal class TcpProxySession
@Inject
internal constructor(
    /** Need to use MutableSet instead of Set because of Java -> Kotlin fun. */
    @ServerInternalApi private val transports: MutableSet<TcpSessionTransport>,
    private val preferences: ServerPreferences,
    private val socketTagger: SocketTagger,
    private val blockedClients: BlockedClients,
    private val clientResolver: ClientResolver,
    private val allowedClients: AllowedClients,
    private val enforcer: ThreadEnforcer,
) : ProxySession<TcpProxyData> {

  @CheckResult
  private suspend fun isTimeoutEnabled(): Boolean {
    return preferences.listenForTimeoutEnabled().first()
  }

  /**
   * Given the initial proxy request, connect to the Internet from our device via the connected
   * socket
   *
   * This function must ALWAYS call connection.usingConnection {} or else a socket may potentially
   * leak
   */
  private suspend inline fun <T> connectToInternet(
      autoFlush: Boolean,
      serverDispatcher: ServerDispatcher,
      request: ProxyRequest,
      socketTracker: SocketTracker,
      block: (ByteReadChannel, ByteWriteChannel) -> T
  ): T =
      usingSocketBuilder(serverDispatcher.primary) { builder ->
        // We don't actually use the socket tls() method here since we are not a TLS server
        // We do the CONNECT based workaround to handle HTTPS connections
        val remote =
            InetSocketAddress(
                hostname = request.host,
                port = request.port,
            )

        val enableTimeout = isTimeoutEnabled()
        val socket =
            builder
                .tcp()
                .configure {
                  reuseAddress = true
                  reusePort = true
                }
                .also { socketTagger.tagSocket() }
                .connect(remoteAddress = remote) {
                  if (enableTimeout) {
                    // By default KTOR does not close sockets until "infinity" is reached.
                    socketTimeout = 1.minutes.inWholeMilliseconds
                  }
                }

        // Track this socket for when we fully shut down
        socketTracker.track(socket)

        return@usingSocketBuilder socket.usingConnection(autoFlush = autoFlush, block)
      }

  private fun CoroutineScope.handleClientRequestSideEffects(
      serverDispatcher: ServerDispatcher,
      client: TetherClient,
  ) {
    enforcer.assertOffMainThread()

    // Mark all client connections as seen
    //
    // We need to do this because we have access to the MAC address via the GroupInfo.clientList
    // but not the IP address. Android does not let us access the system ARP table so we cannot map
    // MACs to IPs. Thus we need to basically hold our own table of "known" IP addresses and allow
    // a user to block them as they see fit. This is UX wise, not great at all, since a user must
    // eliminate a "bad" IP address by first knowing all the good ones.
    //
    // Though, arguably, blocking is only a nice to have. Real network security should be handled
    // via the password.
    launch(context = serverDispatcher.sideEffect) { allowedClients.seen(client) }
  }

  private fun CoroutineScope.handleClientReportSideEffects(
      serverDispatcher: ServerDispatcher,
      report: ByteTransferReport,
      client: TetherClient,
  ) {
    enforcer.assertOffMainThread()

    // Track the report for the given client
    launch(context = serverDispatcher.sideEffect) { allowedClients.reportTransfer(client, report) }
  }

  @CheckResult
  private suspend fun CoroutineScope.proxyToInternet(
      serverDispatcher: ServerDispatcher,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      socketTracker: SocketTracker,
      client: TetherClient,
      transport: TcpSessionTransport,
      request: ProxyRequest,
  ): ByteTransferReport? {
    enforcer.assertOffMainThread()

    // Given the request, connect to the Web
    try {
      val report: ByteTransferReport? =
          connectToInternet(
              autoFlush = true,
              serverDispatcher = serverDispatcher,
              socketTracker = socketTracker,
              request = request,
          ) { internetInput, internetOutput ->
            // Communicate between the web connection we've made and back to our client device
            transport.exchangeInternet(
                scope = this,
                serverDispatcher = serverDispatcher,
                proxyInput = proxyInput,
                proxyOutput = proxyOutput,
                internetInput = internetInput,
                internetOutput = internetOutput,
                request = request,
                client = client,
            )
          }

      return report
    } catch (e: Throwable) {
      e.ifNotCancellation {
        // Generally, the Transport should handle SocketTimeoutException itself.
        // We capture here JUST in case
        if (e is SocketTimeoutException) {
          Timber.w { "Proxy:Internet socket timeout! $request $client" }
        } else {
          Timber.e(e) { "Error during Internet exchange $request $client" }
          writeProxyError(proxyOutput)
        }
      }
      return null
    }
  }

  @CheckResult
  private suspend fun resolveClientOrBlock(
      hostConnection: BroadcastNetworkStatus.ConnectionInfo.Connected,
      hostNameOrIp: String
  ): TetherClient? {
    // If the host is an IP address, and we are an IP address,
    // check that we fall into the host
    if (hostConnection.isIpAddress) {
      if (IP_ADDRESS_REGEX.matches(hostNameOrIp)) {
        if (!hostConnection.isClientWithinAddressableIpRange(hostNameOrIp)) {
          Timber.w { "Reject IP address outside of host range: $hostNameOrIp" }
          return null
        }
      }
    }

    // Retrieve the client (or track if it is it new)
    val client = clientResolver.ensure(hostNameOrIp)

    // If the client is blocked we do not process any input
    if (blockedClients.isBlocked(client)) {
      Timber.w { "Client is marked blocked: $client" }
      return null
    }

    return client
  }

  private suspend fun processRequest(
      scope: CoroutineScope,
      serverDispatcher: ServerDispatcher,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      socketTracker: SocketTracker,
      client: TetherClient,
      transport: TcpSessionTransport,
      request: ProxyRequest,
  ) {
    // This is launched as its own scope so that the side effect does not slow
    // down the internet traffic processing.
    // Since this context is our own dispatcher which is cachedThreadPool backed,
    // we just "spin up" another thread and forget about it performance wise.
    scope.launch(context = serverDispatcher.primary) {
      handleClientRequestSideEffects(
          serverDispatcher = serverDispatcher,
          client = client,
      )
    }

    // And then we go to the web!
    val report =
        scope.proxyToInternet(
            serverDispatcher = serverDispatcher,
            proxyInput = proxyInput,
            proxyOutput = proxyOutput,
            socketTracker = socketTracker,
            transport = transport,
            request = request,
            client = client,
        )

    if (report != null) {
      // This is launched as its own scope so that the side effect does not slow
      // down the internet traffic processing.
      // Since this context is our own dispatcher which is cachedThreadPool backed,
      // we just "spin up" another thread and forget about it performance wise.
      scope.launch(context = serverDispatcher.primary) {
        handleClientReportSideEffects(
            serverDispatcher = serverDispatcher,
            report = report,
            client = client,
        )
      }
    }
  }

  private suspend fun handleClientRequest(
      scope: CoroutineScope,
      hostConnection: BroadcastNetworkStatus.ConnectionInfo.Connected,
      serverDispatcher: ServerDispatcher,
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      socketTracker: SocketTracker,
      hostNameOrIp: String,
  ) {
    val client = resolveClientOrBlock(hostConnection, hostNameOrIp)
    if (client == null) {
      writeClientBlocked(proxyOutput)
      return
    }

    // We use a string parsing to figure out what this HTTP request wants to do
    // Inline to avoid new object allocation
    var transport: TcpSessionTransport? = null
    var request: ProxyRequest? = null
    for (t in transports) {
      val req = t.parseRequest(proxyInput)
      if (req != null) {
        transport = t
        request = req
        break
      }
    }
    if (transport == null || request == null) {
      Timber.w { "Could not parse proxy request $client" }
      writeProxyError(proxyOutput)
      return
    }

    processRequest(
        scope = scope,
        serverDispatcher = serverDispatcher,
        proxyInput = proxyInput,
        proxyOutput = proxyOutput,
        socketTracker = socketTracker,
        client = client,
        transport = transport,
        request = request,
    )
  }

  override suspend fun exchange(
      scope: CoroutineScope,
      hostConnection: BroadcastNetworkStatus.ConnectionInfo.Connected,
      serverDispatcher: ServerDispatcher,
      socketTracker: SocketTracker,
      data: TcpProxyData,
  ) =
      withContext(context = serverDispatcher.primary) {
        val proxyInput = data.proxyInput
        val proxyOutput = data.proxyOutput
        val hostNameOrIp = data.hostNameOrIp
        try {
          handleClientRequest(
              scope = this,
              hostConnection = hostConnection,
              serverDispatcher = serverDispatcher,
              proxyInput = proxyInput,
              proxyOutput = proxyOutput,
              hostNameOrIp = hostNameOrIp,
              socketTracker = socketTracker,
          )
        } catch (e: Throwable) {
          e.ifNotCancellation { Timber.e(e) { "Error handling client Request: $hostNameOrIp" } }
        }
      }
}
