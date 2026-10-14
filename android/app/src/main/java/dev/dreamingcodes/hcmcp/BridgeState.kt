package dev.dreamingcodes.hcmcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { STOPPED, CONNECTING, CONNECTED, OFFLINE, REJECTED }

data class BridgeStatus(
  val phase: Phase = Phase.STOPPED,
  val lastError: String? = null,
  val retryInSec: Long? = null,
  val lastRequestAt: Long? = null,
  val lastRequestMethod: String? = null,
  val connectedSince: Long? = null,
)

/** Process-wide bridge status for the UI. Holds method names and timestamps only, never health values. */
object BridgeState {
  private val _s = MutableStateFlow(BridgeStatus())
  val status: StateFlow<BridgeStatus> = _s
  fun set(f: (BridgeStatus) -> BridgeStatus) = _s.update(f)
}
