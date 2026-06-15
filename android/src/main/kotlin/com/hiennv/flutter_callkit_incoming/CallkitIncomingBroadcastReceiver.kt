package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.LinkedHashSet

class CallkitIncomingBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallkitIncomingReceiver"
        const val EXTRA_CALLKIT_EVENT_SOURCE = "com.hiennv.flutter_callkit_incoming.EVENT_SOURCE"
        private const val EVENT_CALLBACK_BR_KEEP_ALIVE_MS = 9_000L
        private const val CALLBACK_DEDUPE_MAX_ENTRIES = 512
        var silenceEvents = false
        private val callbackDedupeLock = Any()
        private val acceptedCallbackIds = LinkedHashSet<String>()
        private val terminalCallbackIds = LinkedHashSet<String>()

        fun getIntent(context: Context, action: String, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                this.action = "${context.packageName}.${action}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentIncoming(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentStart(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_START}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentAccept(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_ACCEPT}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentDecline(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_DECLINE}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentEnded(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_ENDED}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentTimeout(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_TIMEOUT}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentCallback(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_CALLBACK}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentHeldByCell(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_HELD}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentUnHeldByCell(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_UNHELD}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }

        fun getIntentConnected(context: Context, data: Bundle?) =
            Intent().apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingBroadcastReceiver")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_CONNECTED}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                `package` = context.packageName
            }
    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }

    @SuppressLint("MissingPermission")
    private fun registerTelecomIncomingCall(context: Context, data: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val parsed = try {
            Data.fromBundle(data)
        } catch (e: Exception) {
            null
        } ?: return
        if (parsed.id.isEmpty()) return
        if (CallkitConnection.find(parsed.id) != null) {
            Log.d(TAG, "Telecom call already registered id=${parsed.id} — skip")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "MANAGE_OWN_CALLS not granted — Telecom incoming skipped")
            return
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        val manager = InAppCallManager(context.applicationContext)
        val handle = manager.getPhoneAccountHandle()
        val extras = Bundle().apply {
            putBundle(CallkitConnection.EXTRA_CALL_BUNDLE, data)
            putInt(
                TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                android.telecom.VideoProfile.STATE_AUDIO_ONLY,
            )
        }
        try {
            telecom.addNewIncomingCall(handle, extras)
            Log.d(TAG, "Telecom addNewIncomingCall id=${parsed.id}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Telecom addNewIncomingCall rejected: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Telecom addNewIncomingCall error: ${e.message}")
        }
    }

    private fun driveTelecomConnection(data: Bundle, action: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val parsed = try {
            Data.fromBundle(data)
        } catch (e: Exception) {
            null
        } ?: return
        val conn = CallkitConnection.find(parsed.id) ?: return
        when (action) {
            CallkitConstants.ACTION_CALL_ACCEPT -> conn.markAccepted()
            CallkitConstants.ACTION_CALL_DECLINE -> conn.markDeclined()
            CallkitConstants.ACTION_CALL_ENDED -> conn.markEnded()
            CallkitConstants.ACTION_CALL_TIMEOUT -> conn.markMissed()
        }
    }


    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA) ?: return
        intent.getStringExtra(EXTRA_CALLKIT_EVENT_SOURCE)?.let { data.putString(EXTRA_CALLKIT_EVENT_SOURCE, it) }

        Log.d(TAG, action)

        when (action) {
            "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}" -> {
                try {
                    registerTelecomIncomingCall(context, data)
                    val incomingData = Data.fromBundle(data)
                    if (incomingData.isFullScreen) {
                        val intent = CallkitIncomingActivity.getIntent(context, data)
                        context.startActivity(intent)
                    } else {
                        getCallkitNotificationManager()?.showIncomingNotification(data)
                        sendEventFlutter(CallkitConstants.ACTION_CALL_INCOMING, data)
                        addCall(context, incomingData)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_START}" -> {
                try {
                    // start service and show ongoing call when call is accepted
                    CallkitNotificationService.startServiceWithAction(
                        context,
                        CallkitConstants.ACTION_CALL_START,
                        data
                    )
                    sendEventFlutter(CallkitConstants.ACTION_CALL_START, data)
                    addCall(context, Data.fromBundle(data), true)
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_ACCEPT}" -> {
                try {
                    driveTelecomConnection(data, CallkitConstants.ACTION_CALL_ACCEPT)
                    notifyEventCallbacksOnce(CallkitEventCallback.CallEvent.ACCEPT, data)
                    // start service and show ongoing call when call is accepted
                    CallkitNotificationService.startServiceWithAction(
                        context,
                        CallkitConstants.ACTION_CALL_ACCEPT,
                        data
                    )
                    sendEventFlutter(CallkitConstants.ACTION_CALL_ACCEPT, data)
                    addCall(context, Data.fromBundle(data), true)
                    FlutterCallkitIncomingPlugin.acceptCallHandleCallback(data)
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_DECLINE}" -> {
                keepProcessAliveForEventCallback()
                try {
                    driveTelecomConnection(data, CallkitConstants.ACTION_CALL_DECLINE)
                    notifyEventCallbacksOnce(CallkitEventCallback.CallEvent.DECLINE, data)
                    // clear notification
                    getCallkitNotificationManager()?.clearIncomingNotification(data, false)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_DECLINE, data)
                    removeCall(context, Data.fromBundle(data))
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_ENDED}" -> {
                keepProcessAliveForEventCallback()
                try {
                    driveTelecomConnection(data, CallkitConstants.ACTION_CALL_ENDED)
                    notifyEventCallbacksOnce(CallkitEventCallback.CallEvent.END, data)
                    // clear notification and stop service
                    getCallkitNotificationManager()?.clearIncomingNotification(data, false)
                    CallkitNotificationService.stopService(context)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_ENDED, data)
                    removeCall(context, Data.fromBundle(data))
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_TIMEOUT}" -> {
                keepProcessAliveForEventCallback()
                try {
                    driveTelecomConnection(data, CallkitConstants.ACTION_CALL_TIMEOUT)
                    notifyEventCallbacksOnce(CallkitEventCallback.CallEvent.TIMEOUT, data)
                    // clear notification and show miss notification
                    val notificationManager = getCallkitNotificationManager()
                    notificationManager?.clearIncomingNotification(data, false)
                    notificationManager?.showMissCallNotification(data)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_TIMEOUT, data)
                    removeCall(context, Data.fromBundle(data))
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_CONNECTED}" -> {
                try {
                    // update notification on going connected
                    getCallkitNotificationManager()?.showOngoingCallNotification(data, true)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_CONNECTED, data)
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }

            "${context.packageName}.${CallkitConstants.ACTION_CALL_CALLBACK}" -> {
                try {
                    getCallkitNotificationManager()?.clearMissCallNotification(data)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_CALLBACK, data)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        val closeNotificationPanel = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                        context.sendBroadcast(closeNotificationPanel)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, null, error)
                }
            }
        }
    }


    private fun notifyEventCallbacksOnce(event: CallkitEventCallback.CallEvent, data: Bundle) {
        if (shouldNotifyEventCallbacks(event, data)) {
            FlutterCallkitIncomingPlugin.notifyEventCallbacks(event, data)
        }
    }

    private fun shouldNotifyEventCallbacks(event: CallkitEventCallback.CallEvent, data: Bundle): Boolean {
        val callId = callIdentity(data) ?: return true
        val shouldNotify = synchronized(callbackDedupeLock) {
            when (event) {
                CallkitEventCallback.CallEvent.ACCEPT -> {
                    if (terminalCallbackIds.contains(callId)) {
                        false
                    } else {
                        rememberCallId(acceptedCallbackIds, callId)
                    }
                }
                CallkitEventCallback.CallEvent.DECLINE,
                CallkitEventCallback.CallEvent.END,
                CallkitEventCallback.CallEvent.TIMEOUT -> rememberCallId(terminalCallbackIds, callId)
            }
        }
        if (!shouldNotify) {
            Log.d(
                TAG,
                "Skipping duplicate native callback event=$event id=$callId source=${data.getString(EXTRA_CALLKIT_EVENT_SOURCE)}",
            )
        }
        return shouldNotify
    }

    private fun rememberCallId(set: LinkedHashSet<String>, callId: String): Boolean {
        if (!set.add(callId)) return false
        while (set.size > CALLBACK_DEDUPE_MAX_ENTRIES) {
            val iterator = set.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun callIdentity(data: Bundle): String? {
        data.getString(CallkitConstants.EXTRA_CALLKIT_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val extra = try {
            data.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA) as? HashMap<String, Any?>
        } catch (e: Exception) {
            null
        }
        return extra?.get("callId")?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun keepProcessAliveForEventCallback() {
        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                pendingResult.finish()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to finish PendingResult", e)
            }
        }, EVENT_CALLBACK_BR_KEEP_ALIVE_MS)
    }

    private fun sendEventFlutter(event: String, data: Bundle) {
        if (silenceEvents) return

        val android = mapOf(
            "isCustomNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_NOTIFICATION,
                false
            ),
            "isCustomSmallExNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_SMALL_EX_NOTIFICATION,
                false
            ),
            "ringtonePath" to data.getString(CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH, ""),
            "backgroundColor" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_BACKGROUND_COLOR,
                ""
            ),
            "backgroundUrl" to data.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL, ""),
            "actionColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_ACTION_COLOR, ""),
            "textColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, ""),
            "incomingCallNotificationChannelName" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_INCOMING_CALL_NOTIFICATION_CHANNEL_NAME,
                ""
            ),
            "missedCallNotificationChannelName" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_NOTIFICATION_CHANNEL_NAME,
                ""
            ),
            "isImportant" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_IMPORTANT, true),
            "isBot" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_BOT, false),
        )
        val missedCallNotification = mapOf(
            "id" to data.getInt(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_ID),
            "showNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SHOW),
            "count" to data.getInt(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_COUNT),
            "subtitle" to data.getString(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SUBTITLE),
            "callbackText" to data.getString(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_TEXT),
            "isShowCallback" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_SHOW),
        )
        val callingNotification = mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_ID),
            "showNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW),
            "subtitle" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_SUBTITLE),
            "callbackText" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_TEXT),
            "isShowCallback" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_SHOW),
        )
        val forwardData = mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_ID, ""),
            "nameCaller" to data.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, ""),
            "avatar" to data.getString(CallkitConstants.EXTRA_CALLKIT_AVATAR, ""),
            "number" to data.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, ""),
            "type" to data.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, 0),
            "duration" to data.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L),
            "textAccept" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT, ""),
            "textDecline" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE, ""),
            "acceptColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_ACCEPT_COLOR, ""),
            "declineColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_DECLINE_COLOR, ""),
            "extra" to data.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA),
            "missedCallNotification" to missedCallNotification,
            "callingNotification" to callingNotification,
            "android" to android
        )
        FlutterCallkitIncomingPlugin.sendEvent(event, forwardData)
    }
}
