package com.agent.voiceassistant.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.os.Handler
import android.os.Looper
import com.agent.voiceassistant.service.DiagLog

class AssistantConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        DiagLog.i(
            "telecom.service.create_outgoing",
            "address=${request.address}",
            showInUi = true,
        )
        val connection = AssistantConnection(applicationContext)
        // Telecom applies its outgoing DIALING state after this callback returns.
        Handler(Looper.getMainLooper()).post { connection.activate() }
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        AssistantTelecomRegistry.clearStartRequested()
        DiagLog.w(
            "telecom.service.outgoing_failed",
            "address=${request.address}",
            showInUi = true,
        )
    }
}
