package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-managed Telecom [Connection] implementation for flutter_callkit_incoming.
 *
 * Hosts the call in the Android Telecom framework with `PROPERTY_SELF_MANAGED` so
 * the OS treats it as a first-party phone call — granting it keyguard-bypass /
 * full-screen-intent priority on strict OEMs (Samsung Knox, Xiaomi, etc.) that
 * otherwise ignore [android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED]
 * on ordinary Activities.
 *
 * Lifecycle:
 *  1. [CallkitConnectionService.onCreateIncomingConnection] returns a
 *     ringing Connection instance.
 *  2. User taps Accept in our notification → plugin's BroadcastReceiver maps the
 *     broadcast to the matching connection via [find] and calls [setActive].
 *  3. User taps Decline / End → [setDisconnected] + [destroy] → unregister.
 *
 * Connection lookup uses a process-wide [ConcurrentHashMap] keyed by the call id
 * (the plugin's existing `Data.id` field). The OS holds the Connection object in
 * the Telecom framework; we keep a parallel reference so our BroadcastReceiver
 * can drive state transitions.
 */
@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    context: Context,
    val callId: String,
    val bundle: Bundle,
) : Connection() {

    private val appContext = context.applicationContext
    private val acceptRouted = AtomicBoolean(false)
    private val terminalRouted = AtomicBoolean(false)

    @Volatile
    private var accepted = false

    companion object {
        private const val TAG = "CallkitConnection"

        /** Bundle key — pass the full call Data bundle through Telecom extras. */
        const val EXTRA_CALL_BUNDLE = "com.hiennv.flutter_callkit_incoming.CALL_BUNDLE"

        private val activeConnections = ConcurrentHashMap<String, CallkitConnection>()

        fun find(callId: String): CallkitConnection? = activeConnections[callId]

        fun register(callId: String, conn: CallkitConnection) {
            activeConnections[callId] = conn
        }

        fun unregister(callId: String) {
            activeConnections.remove(callId)
        }

        /** For testing / cleanup — release all refs (Connection objects already destroyed by OS). */
        fun clearAll() {
            activeConnections.clear()
        }

        fun activeCount(): Int = activeConnections.size
    }

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD
        register(callId, this)
        Log.d(TAG, "Connection created id=$callId active=${activeCount()}")
    }

    // -------------------------------------------------------------------------
    // Telecom → app lifecycle callbacks
    //
    // These fire when the user interacts with the OS-level call UI (system
    // dialer, car Bluetooth, watch, etc.). In our app-driven model we still
    // handle the primary Accept/Decline via our own notification buttons, but
    // the OS can also trigger these — we must honor both paths.
    // -------------------------------------------------------------------------

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG, "onAnswer id=$callId")
        setActive()
        routeAccept("telecom_on_answer")
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "onReject id=$callId")
        routeTerminal(
            CallkitConstants.ACTION_CALL_DECLINE,
            "telecom_on_reject",
            DisconnectCause.REJECTED,
        )
    }

    override fun onDisconnect() {
        super.onDisconnect()
        val wasAccepted = accepted
        Log.d(TAG, "onDisconnect id=$callId accepted=$wasAccepted")
        if (wasAccepted) {
            routeTerminal(
                CallkitConstants.ACTION_CALL_ENDED,
                "telecom_on_disconnect_after_accept",
                DisconnectCause.LOCAL,
            )
        } else {
            routeTerminal(
                CallkitConstants.ACTION_CALL_TIMEOUT,
                "telecom_on_disconnect_before_accept",
                DisconnectCause.MISSED,
            )
        }
    }

    override fun onAbort() {
        super.onAbort()
        val wasAccepted = accepted
        Log.d(TAG, "onAbort id=$callId accepted=$wasAccepted")
        if (wasAccepted) {
            routeTerminal(
                CallkitConstants.ACTION_CALL_ENDED,
                "telecom_on_abort_after_accept",
                DisconnectCause.UNKNOWN,
            )
        } else {
            routeTerminal(
                CallkitConstants.ACTION_CALL_TIMEOUT,
                "telecom_on_abort_before_accept",
                DisconnectCause.UNKNOWN,
            )
        }
    }

    override fun onHold() {
        super.onHold()
        setOnHold()
    }

    override fun onUnhold() {
        super.onUnhold()
        setActive()
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        CallAudioRouteManager.onCallAudioStateChanged(callId, state)
    }

    // -------------------------------------------------------------------------
    // App → Telecom driving helpers (invoked by the plugin's BroadcastReceiver)
    // -------------------------------------------------------------------------

    /** Mark the call as answered — user accepted via app notification. */
    fun markAccepted() {
        Log.d(TAG, "markAccepted id=$callId")
        accepted = true
        acceptRouted.compareAndSet(false, true)
        setActive()
    }

    /** Mark the call as declined/ended — user declined via app notification. */
    fun markDeclined() {
        Log.d(TAG, "markDeclined id=$callId")
        terminalRouted.compareAndSet(false, true)
        finishWithCause(DisconnectCause.REJECTED)
    }

    /** Mark the call as terminated — call ended (either side hung up). */
    fun markEnded() {
        Log.d(TAG, "markEnded id=$callId")
        terminalRouted.compareAndSet(false, true)
        finishWithCause(DisconnectCause.LOCAL)
    }

    /** Mark the call as missed — timeout without answer. */
    fun markMissed() {
        Log.d(TAG, "markMissed id=$callId")
        terminalRouted.compareAndSet(false, true)
        finishWithCause(DisconnectCause.MISSED)
    }

    private fun routeAccept(source: String) {
        accepted = true
        if (!acceptRouted.compareAndSet(false, true)) {
            Log.d(TAG, "accept already routed id=$callId source=$source")
            return
        }
        routeToReceiver(CallkitConstants.ACTION_CALL_ACCEPT, source)
    }

    private fun routeTerminal(action: String, source: String, disconnectCause: Int) {
        if (terminalRouted.compareAndSet(false, true)) {
            routeToReceiver(action, source)
        } else {
            Log.d(TAG, "terminal already routed id=$callId source=$source")
        }
        finishWithCause(disconnectCause)
    }

    private fun routeToReceiver(action: String, source: String) {
        val data = Bundle(bundle)
        val intent = when (action) {
            CallkitConstants.ACTION_CALL_ACCEPT ->
                CallkitIncomingBroadcastReceiver.getIntentAccept(appContext, data)
            CallkitConstants.ACTION_CALL_DECLINE ->
                CallkitIncomingBroadcastReceiver.getIntentDecline(appContext, data)
            CallkitConstants.ACTION_CALL_ENDED ->
                CallkitIncomingBroadcastReceiver.getIntentEnded(appContext, data)
            CallkitConstants.ACTION_CALL_TIMEOUT ->
                CallkitIncomingBroadcastReceiver.getIntentTimeout(appContext, data)
            else -> return
        }.apply {
            putExtra(CallkitIncomingBroadcastReceiver.EXTRA_CALLKIT_EVENT_SOURCE, source)
        }
        try {
            appContext.sendBroadcast(intent)
            Log.d(TAG, "routed action=$action id=$callId source=$source")
        } catch (e: Exception) {
            Log.w(TAG, "routeToReceiver failed action=$action id=$callId: ${e.message}")
        }
    }

    private fun finishWithCause(cause: Int) {
        try {
            setDisconnected(DisconnectCause(cause))
        } catch (e: Exception) {
            Log.w(TAG, "setDisconnected failed: ${e.message}")
        }
        unregister(callId)
        try {
            destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
    }
}
