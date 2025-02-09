package com.apero.commons.sms.messaging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Sms
import com.klinker.android.send_message.Settings
import com.apero.commons.sms.messaging.SmsException.Companion.ERROR_PERSISTING_MESSAGE

class MessagingUtils(val context: Context) {

    /**
     * Insert an SMS to the given URI with thread_id specified.
     */
    private fun insertSmsMessage(
        subId: Int, dest: String, text: String, timestamp: Long, threadId: Long,
        status: Int = Sms.STATUS_NONE, type: Int = Sms.MESSAGE_TYPE_OUTBOX, messageId: Long? = null
    ): Uri {
        val response: Uri?
        val values = ContentValues().apply {
            put(Sms.ADDRESS, dest)
            put(Sms.DATE, timestamp)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.BODY, text)

            // insert subscription id only if it is a valid one.
            if (subId != Settings.DEFAULT_SUBSCRIPTION_ID) {
                put(Sms.SUBSCRIPTION_ID, subId)
            }

            if (status != Sms.STATUS_NONE) {
                put(Sms.STATUS, status)
            }
            if (type != Sms.MESSAGE_TYPE_ALL) {
                put(Sms.TYPE, type)
            }
            if (threadId != -1L) {
                put(Sms.THREAD_ID, threadId)
            }
        }

        try {
            if (messageId != null) {
                val selection = "${Sms._ID} = ?"
                val selectionArgs = arrayOf(messageId.toString())
                val count = context.contentResolver.update(Sms.CONTENT_URI, values, selection, selectionArgs)
                if (count > 0) {
                    response = Uri.parse("${Sms.CONTENT_URI}/${messageId}")
                } else {
                    response = null
                }
            } else {
                response = context.contentResolver.insert(Sms.CONTENT_URI, values)
            }
        } catch (e: Exception) {
            throw SmsException(ERROR_PERSISTING_MESSAGE, e)
        }
        return response ?: throw SmsException(ERROR_PERSISTING_MESSAGE)
    }

    companion object {
        const val ADDRESS_SEPARATOR = "|"
    }
}
