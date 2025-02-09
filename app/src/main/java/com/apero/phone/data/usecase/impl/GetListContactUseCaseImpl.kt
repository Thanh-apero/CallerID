package com.apero.phone.data.usecase.impl

import android.content.Context
import com.apero.phone.data.usecase.GetListContactUseCase
import com.apero.commons.dialer.helpers.ContactsHelper
import com.apero.commons.dialer.models.contacts.Contact
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GetListContactUseCaseImpl(val context: Context) : GetListContactUseCase {
    override suspend fun getContact(): List<Contact> = suspendCancellableCoroutine { continuation ->
        ContactsHelper(context).getContacts { contacts ->
            continuation.resume(contacts)
        }
        continuation.invokeOnCancellation {
        }
    }
}