package com.apero.phone.data.usecase

import com.simplemobiletools.commons.models.contacts.Contact

interface GetListContactUseCase {
    suspend fun getContact(): List<Contact>
}