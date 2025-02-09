package com.apero.phone.data.usecase.impl

import android.content.Context
import com.apero.commons.sms.extensions.getConversations
import com.apero.commons.sms.extensions.getMessages
import com.apero.commons.sms.models.Conversation
import com.apero.commons.sms.models.Message
import com.apero.phone.data.usecase.FetchSmsDetailUseCase
import com.apero.phone.data.usecase.FetchSmsListUseCase

class FetchSmsListUseCaseImpl(val context: Context): FetchSmsListUseCase {
    override suspend fun getSmsInbox(): List<Conversation> {
        return context.getConversations()
    }
}