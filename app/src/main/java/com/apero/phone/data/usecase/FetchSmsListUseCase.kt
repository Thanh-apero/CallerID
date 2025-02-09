package com.apero.phone.data.usecase

import com.apero.commons.sms.models.Conversation
import com.apero.commons.sms.models.Message

interface FetchSmsListUseCase {
    suspend fun getSmsInbox(): List<Conversation>
}