package com.apero.phone.services

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection

class CallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val response = CallResponse.Builder()
        
        // Implement your call screening logic here
        // For example, you might want to block known spam numbers
        
        when (callDetails.callDirection) {
            Call.Details.DIRECTION_INCOMING -> {
                response.setDisallowCall(false)
                response.setSkipCallLog(false)
                response.setSkipNotification(false)
            }
            Call.Details.DIRECTION_OUTGOING -> {
                response.setDisallowCall(false)
            }
        }

        respondToCall(callDetails, response.build())
    }
} 