package com.apero.commons.sms.models

data class Conversation(
    var threadId: Long,
    var snippet: String,
    var date: Int,
    var read: Boolean,
    var title: String,
    var photoUri: String,
    var isGroupConversation: Boolean,
    var phoneNumber: String,
    var isScheduled: Boolean = false,
    var usesCustomTitle: Boolean = false,
    var isArchived: Boolean = false
) {

    companion object {
        fun areItemsTheSame(old: Conversation, new: Conversation): Boolean {
            return old.threadId == new.threadId
        }

        fun areContentsTheSame(old: Conversation, new: Conversation): Boolean {
            return old.snippet == new.snippet &&
                old.date == new.date &&
                old.read == new.read &&
                old.title == new.title &&
                old.photoUri == new.photoUri &&
                old.isGroupConversation == new.isGroupConversation &&
                old.phoneNumber == new.phoneNumber
        }
    }
}
