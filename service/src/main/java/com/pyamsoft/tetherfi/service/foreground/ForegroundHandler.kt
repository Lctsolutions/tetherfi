package com.pyamsoft.tetherfi.service.foreground

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventConsumer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.tetherfi.server.event.ServerShutdownEvent
import com.pyamsoft.tetherfi.server.status.RunningStatus
import com.pyamsoft.tetherfi.server.widi.WiDiNetwork
import com.pyamsoft.tetherfi.server.widi.WiDiNetworkStatus
import com.pyamsoft.tetherfi.service.ServiceInternalApi
import com.pyamsoft.tetherfi.service.lock.Locker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class ForegroundHandler
@Inject
internal constructor(
    @ServiceInternalApi private val locker: Locker,
    private val shutdownBus: EventConsumer<ServerShutdownEvent>,
    private val network: WiDiNetwork,
    private val status: WiDiNetworkStatus,
) {

  private val scope by lazy(LazyThreadSafetyMode.NONE) { MainScope() }

  private var prepJob: Job? = null

  private suspend fun acquireCpuWakeLock() {
    Timber.d("Attempt to claim CPU wakelock")
    locker.acquire()
  }

  private suspend fun releaseCpuWakeLock() {
    Timber.d("Attempt to release CPU wakelock")
    locker.release()
  }

  private fun killJobs() {
    prepJob?.cancel()
    prepJob = null
  }

  @CheckResult
  private fun Job?.cancelAndReLaunch(block: suspend CoroutineScope.() -> Unit): Job {
    this?.cancel()
    return scope.launch(context = Dispatchers.Main, block = block)
  }

  fun prepareProxy(
      onShutdownService: () -> Unit,
  ) {
    prepJob =
        prepJob.cancelAndReLaunch {
          // When shutdown events are received, we kill the service
          launch(context = Dispatchers.Main) {
            Timber.d("Watching for Shutdown")
            shutdownBus.requireNotNull().onEvent {
              Timber.d("Shutdown event received!")
              onShutdownService()
            }
          }

          // Watch status of network
          launch(context = Dispatchers.Main) {
            status.requireNotNull().onStatusChanged { s ->
              when (s) {
                is RunningStatus.Error -> {
                  Timber.w("Server Server Error: ${s.message}")
                  // Release wakelock
                  releaseCpuWakeLock()
                }
                else -> Timber.d("Server status changed: $s")
              }
            }
          }

          // Watch status of proxy
          launch(context = Dispatchers.Main) {
            status.requireNotNull().onProxyStatusChanged { s ->
              when (s) {
                is RunningStatus.Running -> {
                  Timber.d("Proxy Server started!")
                  // Acquire wake lock
                  acquireCpuWakeLock()
                }
                is RunningStatus.Error -> {
                  Timber.w("Proxy Server Error: ${s.message}")
                  // Release wakelock
                  releaseCpuWakeLock()
                }
                else -> Timber.d("Proxy status changed: $s")
              }
            }
          }
        }
  }

  fun startProxy() {
    Timber.d("Start WiDi Network")
    network.start()
  }

  fun stopProxy() {
    killJobs()

    Timber.d("Stop WiDi network")
    network.stop()

    // Launch a parent scope for all jobs
    scope.launch(context = Dispatchers.Main) {
      Timber.d("Destroy CPU wakelock")
      locker.release()
    }
  }
}
