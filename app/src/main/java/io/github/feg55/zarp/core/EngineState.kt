package io.github.feg55.zarp.core

enum class EngineState {
    Idle,
    Preparing,
    Searching,
    Connecting,
    Connected,
    Disconnecting,
    Error;

    val busy: Boolean get() = this == Preparing || this == Searching || this == Connecting || this == Disconnecting
}

/** Everything the UI shows about the engine. */
data class EngineStatus(
    val state: EngineState = EngineState.Idle,
    val detail: Msg = Msg("detail.noStrategy"),
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** Strategy being tested or the one the tunnel runs with. */
    val strategy: Strategy? = null,
    val connectMs: Int? = null,
    val pingMs: Int? = null,
    val endpoint: String? = null,
)
