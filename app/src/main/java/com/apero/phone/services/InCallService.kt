package com.apero.phone.services

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class InCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d("InCallService", "Call added")
        // Handle incoming/outgoing call
        // You would typically start your InCall UI Activity here
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("InCallService", "Call removed")
        // Clean up any UI/resources
    }
} 