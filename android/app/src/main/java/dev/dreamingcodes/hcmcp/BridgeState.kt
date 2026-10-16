package dev.dreamingcodes.hcmcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class BridgeStatus(
  val lastRequestAt: Long? = null,
  val lastRequestMethod: String? = null,
  val pushRegisteredAt: Long? = null,
  val pushError: String? = null,
  val registering: Boolean = false,
)

object BridgeState {
  private val _s = MutableStateFlow(BridgeStatus())
  val status: StateFlow<BridgeStatus> = _s
  fun set(f: (BridgeStatus) -> BridgeStatus) = _s.update(f)
}
