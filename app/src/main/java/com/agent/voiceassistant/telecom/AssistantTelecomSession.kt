package com.agent.voiceassistant.telecom

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.agent.voiceassistant.service.DiagLog

/**
 * Owns the single self-managed Telecom call that represents an active assistant session.
 * Conversation and audio logic remain in VoiceAgentService.
 */
class AssistantTelecomSession(context: Context) {
    private val appContext = context.applicationContext
    private val telecomManager = appContext.getSystemService(TelecomManager::class.java)
    private val accountHandle = PhoneAccountHandle(
        ComponentName(appContext, AssistantConnectionService::class.java),
        ACCOUNT_ID,
    )

    fun register(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || telecomManager == null) {
            DiagLog.w("telecom.unsupported", "sdk=${Build.VERSION.SDK_INT}", showInUi = true)
            return false
        }

        return runCatching {
            val account = PhoneAccount.builder(accountHandle, ACCOUNT_LABEL)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .build()
            telecomManager.registerPhoneAccount(account)
            DiagLog.i("telecom.account.registered", "id=$ACCOUNT_ID")
            true
        }.onFailure {
            DiagLog.w(
                "telecom.account.failed",
                "${it.javaClass.simpleName}:${it.message}",
                showInUi = true,
            )
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun beginListening(): Boolean {
        if (!register()) return false
        if (AssistantTelecomRegistry.hasLiveConnection()) {
            DiagLog.i("telecom.call.reuse", "state=live")
            return true
        }
        if (!AssistantTelecomRegistry.markStartRequested()) {
            DiagLog.i("telecom.call.reuse", "state=requested")
            return true
        }

        return runCatching {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
            }
            telecomManager.placeCall(ASSISTANT_ADDRESS, extras)
            DiagLog.i("telecom.call.requested", "account=$ACCOUNT_ID", showInUi = true)
            true
        }.onFailure {
            AssistantTelecomRegistry.clearStartRequested()
            DiagLog.w(
                "telecom.call.request_failed",
                "${it.javaClass.simpleName}:${it.message}",
                showInUi = true,
            )
        }.getOrDefault(false)
    }

    fun endListening(reason: String) {
        DiagLog.i("telecom.call.end_requested", "reason=$reason")
        AssistantTelecomRegistry.disconnectFromApp(reason)
    }

    companion object {
        private const val ACCOUNT_ID = "voice-assistant-session"
        private const val ACCOUNT_LABEL = "语音助手"
        private val ASSISTANT_ADDRESS: Uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, "0000", null)
    }
}

internal object AssistantTelecomRegistry {
    private val lock = Any()
    private var connection: AssistantConnection? = null
    private var startRequested = false

    fun markStartRequested(): Boolean = synchronized(lock) {
        if (startRequested || connection != null) return@synchronized false
        startRequested = true
        true
    }

    fun clearStartRequested() = synchronized(lock) {
        startRequested = false
    }

    fun attach(value: AssistantConnection) = synchronized(lock) {
        connection = value
        startRequested = false
    }

    fun detach(value: AssistantConnection) = synchronized(lock) {
        if (connection === value) connection = null
        startRequested = false
    }

    fun hasLiveConnection(): Boolean = synchronized(lock) {
        connection != null
    }

    fun disconnectFromApp(reason: String) {
        val active = synchronized(lock) {
            startRequested = false
            connection
        }
        active?.disconnectFromApp(reason)
    }
}

internal class AssistantConnection(
    private val context: Context,
) : android.telecom.Connection() {
    private var closed = false

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_SUPPORT_HOLD
        setAudioModeIsVoip(true)
        setDialing()
    }

    fun activate() {
        AssistantTelecomRegistry.attach(this)
        setActive()
        DiagLog.i("telecom.connection.active", showInUi = true)
    }

    override fun onDisconnect() {
        DiagLog.i("telecom.connection.remote_disconnect", showInUi = true)
        close(DisconnectCause.REMOTE, "headset_or_system")
        com.agent.voiceassistant.service.VoiceAgentService.sleep(context)
    }

    override fun onAbort() {
        DiagLog.i("telecom.connection.abort", showInUi = true)
        close(DisconnectCause.CANCELED, "abort")
        com.agent.voiceassistant.service.VoiceAgentService.sleep(context)
    }

    override fun onReject() {
        DiagLog.i("telecom.connection.reject", showInUi = true)
        close(DisconnectCause.REJECTED, "reject")
        com.agent.voiceassistant.service.VoiceAgentService.sleep(context)
    }

    override fun onAnswer() {
        DiagLog.i("telecom.connection.answer")
        if (!closed) setActive()
    }

    override fun onHold() {
        DiagLog.i("telecom.connection.hold", showInUi = true)
        if (!closed) setOnHold()
    }

    override fun onUnhold() {
        DiagLog.i("telecom.connection.unhold", showInUi = true)
        if (!closed) setActive()
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        DiagLog.i(
            "telecom.connection.audio",
            "route=${state.route} supported=${state.supportedRouteMask} muted=${state.isMuted}",
        )
    }

    fun disconnectFromApp(reason: String) {
        DiagLog.i("telecom.connection.local_disconnect", "reason=$reason")
        close(DisconnectCause.LOCAL, reason)
    }

    private fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        AssistantTelecomRegistry.detach(this)
        setDisconnected(DisconnectCause(code, reason))
        destroy()
    }
}
