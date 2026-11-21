package io.github.daniele21.harnex.hello

import android.content.Context
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneClient
import io.github.daniele21.localllm.contracts.ConsumerLocalLlmClient
import io.github.daniele21.localllm.transport.binder.client.BinderConsumerLocalLlmClient
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionObserver
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeHostConfig

/**
 * App-owned test seam composed from the SDK contracts.
 *
 * Keep connection intent explicit, but delegate Consumer inference and Host control-plane operations
 * to the actual public SDK. Avoid forwarding every method in each new Android consumer.
 */
internal interface HelloHarnexRuntime :
    ConsumerLocalLlmClient,
    ConsumerControlPlaneClient,
    AutoCloseable {
    fun connect()

    fun disconnect()
}

internal class BinderHelloHarnexRuntime private constructor(private val client: BinderConsumerLocalLlmClient) :
    HelloHarnexRuntime,
    ConsumerLocalLlmClient by client,
    ConsumerControlPlaneClient by client {
    override fun connect() = client.connect()

    override fun disconnect() = client.disconnect()

    override fun close() = client.close()

    companion object {
        fun create(context: Context, onConnectionChanged: SharedRuntimeConnectionObserver): BinderHelloHarnexRuntime =
            BinderHelloHarnexRuntime(
                BinderConsumerLocalLlmClient.create(
                    context = context.applicationContext,
                    hostConfig =
                    SharedRuntimeHostConfig.create(
                        BuildConfig.HARNEX_HOST_PACKAGE,
                        HARNEX_HOST_SERVICE,
                    ),
                    clientBuildId = "hello-harnex-${BuildConfig.VERSION_NAME}",
                    observer = onConnectionChanged,
                ),
            )
    }
}

internal const val HARNEX_HOST_SERVICE = "io.github.daniele21.localllm.phonetest.HarnessSharedRuntimeService"
