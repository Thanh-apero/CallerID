package com.apero.commons.sms.models

import android.provider.Telephony
import com.apero.commons.dialer.models.SimpleContact

data class Message(
    val id: Long,
    val body: String,
    val type: Int,
    val status: Int,
    val participants: ArrayList<SimpleContact>,
    val date: Int,
    val read: Boolean,
    val threadId: Long,
    val isMMS: Boolean,
    val attachment: MessageAttachment?,
    val senderPhoneNumber: String,
    var senderName: String,
    val senderPhotoUri: String,
    var subscriptionId: Int,
    var isScheduled: Boolean = false
) : ThreadItem() {

    fun isReceivedMessage() = type == Telephony.Sms.MESSAGE_TYPE_INBOX

    fun millis() = date * 1000L

    fun getSender(): SimpleContact? =
        participants.firstOrNull { it.doesHavePhoneNumber(senderPhoneNumber) }
            ?: participants.firstOrNull { it.name == senderName }
            ?: participants.firstOrNull()

    companion object {

        fun getStableId(message: Message): Long {
            var result = message.id.hashCode()
            result = 31 * result + message.body.hashCode()
            result = 31 * result + message.date.hashCode()
            result = 31 * result + message.threadId.hashCode()
            result = 31 * result + message.isMMS.hashCode()
            result = 31 * result + (message.attachment?.hashCode() ?: 0)
            result = 31 * result + message.senderPhoneNumber.hashCode()
            result = 31 * result + message.senderName.hashCode()
            result = 31 * result + message.senderPhotoUri.hashCode()
            result = 31 * result + message.isScheduled.hashCode()
            return result.toLong()
        }

        fun areItemsTheSame(old: Message, new: Message): Boolean {
            return old.id == new.id
        }

        fun areContentsTheSame(old: Message, new: Message): Boolean {
            return old.body == new.body &&
                old.threadId == new.threadId &&
                old.date == new.date &&
                old.isMMS == new.isMMS &&
                old.attachment == new.attachment &&
                old.senderPhoneNumber == new.senderPhoneNumber &&
                old.senderName == new.senderName &&
                old.senderPhotoUri == new.senderPhotoUri &&
                old.isScheduled == new.isScheduled
        }
    }
}
