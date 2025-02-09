package com.apero.phone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            messages?.forEach { smsMessage ->
                Log.d("SmsReceiver", "From: ${smsMessage.originatingAddress}")
                Log.d("SmsReceiver", "Message: ${smsMessage.messageBody}")
                
                // Here you would typically:
                // 1. Store the message in your database
                // 2. Update the SMS Provider
                // 3. Notify the user
                // 4. Update your UI if necessary
            }
        }
    }
} 