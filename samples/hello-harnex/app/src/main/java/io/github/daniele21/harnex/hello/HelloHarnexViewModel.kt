package io.github.daniele21.harnex.hello

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionSnapshot
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionState

/**
 * Owns the Consumer SDK client across Activity recreation.
 *
 * Product UI renders this state and sends user intents; Binder/runtime lifecycle stays outside the Activity.
 */
internal class HelloHarnexViewModel(context: Context) : ViewModel() {
    private val stateLock = Any()
    private var currentState = HelloHarnexUiState()
    private val mutableState = MutableLiveData(currentState)
    val state: LiveData<HelloHarnexUiState> = mutableState

    private val client = HelloHarnexClient.create(context.applicationContext, ::onConnectionChanged)

    fun updateInput(value: String) {
        updateState { copy(input = value) }
    }

    fun connect() {
        updateState { copy(status = "Connecting to the exact configured Harnex service…") }
        runCatching { client.connect() }.onFailure(::showFailure)
    }

    fun disconnect() {
        runCatching { client.disconnect() }
            .onSuccess {
                updateState {
                    copy(status = "Disconnected. Connect again to start a fresh authorization epoch.")
                }
            }
            .onFailure(::showFailure)
    }

    fun runInference() {
        val text = synchronized(stateLock) { currentState.input.trim() }
        if (text.isBlank()) {
            updateState { copy(status = "Enter some text first.") }
            return
        }
        updateState {
            copy(
                inferenceRunning = true,
                output = "",
                metrics = "",
                status = "Starting local inference…",
            )
        }
        client.run(
            text = text,
            onStatus = { status -> updateState { copy(status = status) } },
            onAnswerDelta = { delta -> updateState { copy(output = output + delta) } },
            onResult = { result ->
                result.fold(
                    onSuccess = { inference ->
                        updateState {
                            copy(
                                inferenceRunning = false,
                                status = "Completed locally through Harnex.",
                                output = inference.answer,
                                metrics = inference.metrics,
                            )
                        }
                    },
                    onFailure = { failure ->
                        updateState { copy(inferenceRunning = false) }
                        showFailure(failure)
                    },
                )
            },
        )
    }

    fun cancel() {
        updateState { copy(status = "Cancellation requested…") }
        client.cancel()
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }

    private fun onConnectionChanged(snapshot: SharedRuntimeConnectionSnapshot) {
        updateState {
            copy(
                connectionState = snapshot.state,
                negotiatedMinor = snapshot.negotiatedMinor,
                status = snapshot.detail ?: status,
            )
        }
    }

    private fun showFailure(error: Throwable) {
        val message =
            when (error) {
                is HelloHarnexException -> error.userMessage
                else -> "Failed: ${error.message ?: error::class.java.simpleName}"
            }
        updateState { copy(status = message) }
    }

    private fun updateState(transform: HelloHarnexUiState.() -> HelloHarnexUiState) {
        val next = synchronized(stateLock) {
            currentState = currentState.transform()
            currentState
        }
        mutableState.postValue(next)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HelloHarnexViewModel::class.java)) {
                "Unsupported ViewModel ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return HelloHarnexViewModel(context.applicationContext) as T
        }
    }
}

internal data class HelloHarnexUiState(
    val connectionState: SharedRuntimeConnectionState = SharedRuntimeConnectionState.DISCONNECTED,
    val negotiatedMinor: Int? = null,
    val input: String = "Explain in two concise sentences why on-device AI can improve privacy.",
    val output: String = "No inference yet.",
    val metrics: String = "",
    val status: String = "Authorize the app in Harnex, then connect.",
    val inferenceRunning: Boolean = false,
) {
    val connected: Boolean
        get() = connectionState == SharedRuntimeConnectionState.CONNECTED
}
