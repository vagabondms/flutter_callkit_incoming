package com.hiennv.flutter_callkit_incoming

import android.bluetooth.BluetoothDevice
import android.os.Build
import android.telecom.CallAudioState
import android.util.Log
import androidx.annotation.RequiresApi
import io.flutter.plugin.common.EventChannel
import java.util.concurrent.CopyOnWriteArrayList

@RequiresApi(Build.VERSION_CODES.M)
object CallAudioRouteManager {
    private const val TAG = "CallAudioRouteManager"

    const val DEVICE_BUILTIN_RECEIVER = "builtin_receiver"
    const val DEVICE_BUILTIN_SPEAKER = "builtin_speaker"
    const val DEVICE_WIRED_HEADSET = "wired_headset"

    private val sinks = CopyOnWriteArrayList<EventChannel.EventSink>()

    fun createStreamHandler(): EventChannel.StreamHandler = object : EventChannel.StreamHandler {
        private var sink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
            sink?.let(sinks::remove)
            sink = events
            sinks.add(events)
        }

        override fun onCancel(arguments: Any?) {
            sink?.let(sinks::remove)
            sink = null
        }
    }

    fun getDevices(callId: String): List<Map<String, Any?>> {
        val connection = CallkitConnection.find(callId) ?: return emptyList()
        return buildDevices(connection)
    }

    fun getRoute(callId: String): String {
        val connection = CallkitConnection.find(callId) ?: return "unknown"
        return routeName(connection.callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE)
    }

    fun setRoute(callId: String, deviceId: String): Boolean {
        val connection = CallkitConnection.find(callId) ?: return false
        return try {
            when (deviceId) {
                DEVICE_BUILTIN_RECEIVER -> connection.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                DEVICE_BUILTIN_SPEAKER -> connection.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                DEVICE_WIRED_HEADSET -> connection.setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
                else -> {
                    val bluetooth = connection.callAudioState
                        ?.supportedBluetoothDevices
                        ?.firstOrNull { bluetoothDeviceId(it) == deviceId }
                    if (bluetooth == null) {
                        Log.w(TAG, "Bluetooth device not found: $deviceId")
                        return false
                    }
                    connection.requestBluetoothAudio(bluetooth)
                }
            }
            emitRequestedSnapshot(callId, deviceId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setRoute failed: callId=$callId deviceId=$deviceId", e)
            false
        }
    }

    fun onCallAudioStateChanged(callId: String, state: CallAudioState) {
        emitSnapshot(callId, state)
    }

    private fun emitSnapshot(callId: String, state: CallAudioState? = null) {
        if (sinks.isEmpty()) return
        val connection = CallkitConnection.find(callId)
        val routeState = state ?: connection?.callAudioState
        val body = mapOf(
            "callId" to callId,
            "route" to routeName(routeState?.route ?: CallAudioState.ROUTE_EARPIECE),
            "devices" to if (connection != null) buildDevices(connection, routeState) else emptyList(),
        )
        sinks.forEach { sink -> sink.success(body) }
    }

    private fun emitRequestedSnapshot(callId: String, deviceId: String) {
        if (sinks.isEmpty()) return
        val connection = CallkitConnection.find(callId)
        val devices = if (connection != null) {
            buildDevices(connection).map { device ->
                device + ("isSelected" to (device["id"] == deviceId))
            }
        } else {
            emptyList()
        }
        val body = mapOf(
            "callId" to callId,
            "route" to routeNameForDeviceId(deviceId),
            "devices" to devices,
        )
        sinks.forEach { sink -> sink.success(body) }
    }

    private fun buildDevices(
        connection: CallkitConnection,
        state: CallAudioState? = connection.callAudioState,
    ): List<Map<String, Any?>> {
        val route = state?.route ?: CallAudioState.ROUTE_EARPIECE
        val supportedRouteMask = state?.supportedRouteMask ?: (
            CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER
        )
        val devices = mutableListOf<Map<String, Any?>>()

        if (supports(supportedRouteMask, CallAudioState.ROUTE_EARPIECE)) {
            devices.add(device(DEVICE_BUILTIN_RECEIVER, "Phone", "receiver", route == CallAudioState.ROUTE_EARPIECE))
        }
        if (supports(supportedRouteMask, CallAudioState.ROUTE_SPEAKER)) {
            devices.add(device(DEVICE_BUILTIN_SPEAKER, "Speaker", "speaker", route == CallAudioState.ROUTE_SPEAKER))
        }
        if (supports(supportedRouteMask, CallAudioState.ROUTE_WIRED_HEADSET)) {
            devices.add(device(DEVICE_WIRED_HEADSET, "Wired headset", "wiredHeadset", route == CallAudioState.ROUTE_WIRED_HEADSET))
        }

        state?.supportedBluetoothDevices?.forEach { bluetooth ->
            val id = bluetoothDeviceId(bluetooth)
            val active = state.activeBluetoothDevice?.let { bluetoothDeviceId(it) } == id
            devices.add(device(id, bluetoothDisplayName(bluetooth), "bluetooth", route == CallAudioState.ROUTE_BLUETOOTH && active))
        }

        return devices
    }

    private fun device(
        id: String,
        name: String,
        route: String,
        selected: Boolean,
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "route" to route,
        "isSelected" to selected,
    )

    private fun supports(mask: Int, route: Int): Boolean = mask and route == route

    private fun routeName(route: Int): String = when (route) {
        CallAudioState.ROUTE_EARPIECE -> "receiver"
        CallAudioState.ROUTE_SPEAKER -> "speaker"
        CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
        CallAudioState.ROUTE_WIRED_HEADSET -> "wiredHeadset"
        else -> "unknown"
    }

    private fun routeNameForDeviceId(deviceId: String): String = when {
        deviceId == DEVICE_BUILTIN_RECEIVER -> "receiver"
        deviceId == DEVICE_BUILTIN_SPEAKER -> "speaker"
        deviceId == DEVICE_WIRED_HEADSET -> "wiredHeadset"
        deviceId.startsWith("bluetooth:") -> "bluetooth"
        else -> "unknown"
    }

    private fun bluetoothDeviceId(device: BluetoothDevice): String {
        val address = try {
            device.address
        } catch (_: SecurityException) {
            null
        }
        return "bluetooth:${address ?: device.hashCode().toString()}"
    }

    private fun bluetoothDisplayName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Bluetooth"
        } catch (_: SecurityException) {
            "Bluetooth"
        }
    }
}
