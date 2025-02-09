package com.apero.phone.data.usecase

import com.apero.commons.sms.models.Message

interface FetchSmsDetailUseCase {
    suspend fun getSmsDetail(threadId: Long): List<Message>
}