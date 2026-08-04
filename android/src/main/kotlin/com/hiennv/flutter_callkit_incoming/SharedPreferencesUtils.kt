package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference


private const val CALLKIT_PREFERENCES_FILE_NAME = "flutter_callkit_incoming"
private const val SHARED_PREFS_TAG = "CallkitSharedPrefs"
private var prefs: SharedPreferences? = null
private var editor: SharedPreferences.Editor? = null

private fun initInstance(context: Context) {
    prefs = context.getSharedPreferences(CALLKIT_PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    editor = prefs?.edit()
}

fun addBackgroundCallback(context: Context?, pluginHandler: Long, userHandle: Long) {
    putLong(context, "CALLBACK_HANDLE", pluginHandler)
    putLong(context, "CALLBACK_USER_HANDLE", userHandle)
}

fun addCall(context: Context?, data: Data, isAccepted: Boolean = false) {
    val arrayData = readActiveCalls(context)
    val currentData = arrayData.find { it == data }
    if(currentData != null) {
        currentData.isAccepted = isAccepted
    }else {
        data.isAccepted = isAccepted
        arrayData.add(data)
    }
    putString(context, "ACTIVE_CALLS", Utils.getGsonInstance().writeValueAsString(arrayData))
}

fun removeCall(context: Context?, data: Data) {
    val arrayData = readActiveCalls(context)
    arrayData.remove(data)
    putString(context, "ACTIVE_CALLS", Utils.getGsonInstance().writeValueAsString(arrayData))
}

fun removeAllCalls(context: Context?) {
    putString(context, "ACTIVE_CALLS", "[]")
    remove(context, "ACTIVE_CALLS")
}

fun getPluginCallbackHandle(context: Context?): Long? {
    return getLong(context, "CALLBACK_HANDLE", 0L)
}

fun getUserCallback(context: Context?): Long? {
    return getLong(context, "CALLBACK_USER_HANDLE", 0L)
}

fun getDataActiveCalls(context: Context?): ArrayList<Data> {
    return readActiveCalls(context)
}

fun getDataActiveCallsForFlutter(context: Context?): ArrayList<Map<String, Any?>> {
    val json = getString(context, "ACTIVE_CALLS", "[]") ?: "[]"
    return try {
        Utils.getGsonInstance().readValue(json, object : TypeReference<ArrayList<Map<String, Any?>>>() {})
    } catch (e: Exception) {
        Log.w(SHARED_PREFS_TAG, "ACTIVE_CALLS unreadable — resetting to empty: ${e.message}")
        putString(context, "ACTIVE_CALLS", "[]")
        arrayListOf()
    }
}

/**
 * Every ACTIVE_CALLS access is read-first, so a payload the current parser
 * cannot read would otherwise wedge the store permanently (no write path is
 * ever reached to overwrite it — endAllCalls included). Reset-on-failure
 * keeps the invariant: this store can always be read.
 */
private fun readActiveCalls(context: Context?): ArrayList<Data> {
    val json = getString(context, "ACTIVE_CALLS", "[]") ?: "[]"
    return try {
        Utils.getGsonInstance().readValue(json, object : TypeReference<ArrayList<Data>>() {})
    } catch (e: Exception) {
        Log.w(SHARED_PREFS_TAG, "ACTIVE_CALLS unreadable — resetting to empty: ${e.message}")
        putString(context, "ACTIVE_CALLS", "[]")
        arrayListOf()
    }
}

fun putLong(context: Context?, key: String, value: Long) {
    if (context == null) return
    initInstance(context)
    editor?.putLong(key, value)
    editor?.commit()
}

fun putString(context: Context?, key: String, value: String?) {
    if (context == null) return
    initInstance(context)
    editor?.putString(key, value)
    editor?.commit()
}

fun getString(context: Context?, key: String, defaultValue: String = ""): String? {
    if (context == null) return null
    initInstance(context)
    return prefs?.getString(key, defaultValue)
}

fun getLong(context: Context?, key: String, defaultValue: Long = 0L): Long? {
    if (context == null) return defaultValue
    initInstance(context)
    return prefs?.getLong(key, defaultValue)
}

fun remove(context: Context?, key: String) {
    if (context == null) return
    initInstance(context)
    editor?.remove(key)
    editor?.commit()
}

fun saveHandle(context: Context?, key: String, handle: Int) {
    if (context == null) return
    initInstance(context)
    editor?.putInt(key, handle)
    editor?.commit()
}

fun getRawHandle(context: Context?, key: String): Int? {
    if (context == null) return null
    initInstance(context)
    return prefs?.getInt(key, 0)
}
