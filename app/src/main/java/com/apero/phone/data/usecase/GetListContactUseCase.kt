package com.apero.phone.data.usecase

import com.apero.commons.dialer.models.contacts.Contact

interface GetListContactUseCase {
    suspend fun getContact(): List<Contact>
}