package com.apero.phone.data.usecase.impl

import android.content.Context
import com.apero.commons.sms.extensions.getMessages
import com.apero.commons.sms.models.Message
import com.apero.phone.data.usecase.FetchSmsDetailUseCase

class FetchSmsDetailUseCaseImpl(val context: Context): FetchSmsDetailUseCase {
    override suspend fun getSmsDetail(threadId: Long): List<Message> {
        return context.getMessages(threadId, false)
    }
}