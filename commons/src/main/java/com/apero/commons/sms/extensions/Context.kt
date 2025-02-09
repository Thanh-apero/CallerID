package com.apero.commons.sms.extensions

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.provider.OpenableColumns
import android.provider.Telephony.*
import android.telephony.SubscriptionManager
import android.text.TextUtils
import com.apero.commons.dialer.extensions.*
import com.apero.commons.dialer.helpers.MyContactsContentProvider
import com.apero.commons.dialer.helpers.PERMISSION_READ_CONTACTS
import com.apero.commons.dialer.helpers.SimpleContactsHelper
import com.apero.commons.dialer.helpers.isQPlus
import com.apero.commons.dialer.models.PhoneNumber
import com.apero.commons.dialer.models.SimpleContact
import com.apero.commons.sms.helpers.Config
import com.apero.commons.sms.helpers.FILE_SIZE_NONE
import com.apero.commons.sms.helpers.MESSAGES_LIMIT
import com.apero.commons.sms.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import com.apero.commons.sms.models.Attachment
import com.apero.commons.sms.models.Conversation
import com.apero.commons.sms.models.Message
import com.apero.commons.sms.models.MessageAttachment
import com.apero.commons.sms.models.NamePhoto
import java.io.FileNotFoundException

val Context.config: Config get() = Config.newInstance(applicationContext)

fun Context.getMessages(
    threadId: Long,
    getImageResolutions: Boolean,
    dateFrom: Int = -1,
    includeScheduledMessages: Boolean = true,
    limit: Int = MESSAGES_LIMIT
): ArrayList<Message> {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms._ID,
        Sms.BODY,
        Sms.TYPE,
        Sms.ADDRESS,
        Sms.DATE,
        Sms.READ,
        Sms.THREAD_ID,
        Sms.SUBSCRIPTION_ID,
        Sms.STATUS
    )

    val rangeQuery = if (dateFrom == -1) "" else "AND ${Sms.DATE} < ${dateFrom.toLong() * 1000}"
    val selection = "${Sms.THREAD_ID} = ? $rangeQuery"
    val selectionArgs = arrayOf(threadId.toString())
    val sortOrder = "${Sms.DATE} DESC LIMIT $limit"

    val blockStatus = HashMap<String, Boolean>()
    val blockedNumbers = getBlockedNumbers()
    var messages = ArrayList<Message>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor

        val isNumberBlocked = if (blockStatus.containsKey(senderNumber)) {
            blockStatus[senderNumber]!!
        } else {
            val isBlocked = isNumberBlocked(senderNumber, blockedNumbers)
            blockStatus[senderNumber] = isBlocked
            isBlocked
        }

        if (isNumberBlocked) {
            return@queryCursor
        }

        val id = cursor.getLongValue(Sms._ID)
        val body = cursor.getStringValue(Sms.BODY)
        val type = cursor.getIntValue(Sms.TYPE)
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        val senderName = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val date = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        val read = cursor.getIntValue(Sms.READ) == 1
        val thread = cursor.getLongValue(Sms.THREAD_ID)
        val subscriptionId = cursor.getIntValue(Sms.SUBSCRIPTION_ID)
        val status = cursor.getIntValue(Sms.STATUS)
        val participants = senderNumber.split(ADDRESS_SEPARATOR).map { number ->
            val phoneNumber = PhoneNumber(number, 0, "", number)
            val participantPhoto = getNameAndPhotoFromPhoneNumber(number)
            SimpleContact(0, 0, participantPhoto.name, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
        }
        val isMMS = false
        val message =
            Message(
                id,
                body,
                type,
                status,
                ArrayList(participants),
                date,
                read,
                thread,
                isMMS,
                null,
                senderNumber,
                senderName,
                photoUri,
                subscriptionId
            )
        messages.add(message)
    }

    messages.addAll(getMMS(threadId, getImageResolutions, sortOrder, dateFrom))

    messages = messages
        .filter { it.participants.isNotEmpty() }
        .filterNot { it.isScheduled && it.millis() < System.currentTimeMillis() }
        .sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
        .takeLast(limit)
        .toMutableList() as ArrayList<Message>

    return messages
}

// as soon as a message contains multiple recipients it counts as an MMS instead of SMS
fun Context.getMMS(threadId: Long? = null, getImageResolutions: Boolean = false, sortOrder: String? = null, dateFrom: Int = -1): ArrayList<Message> {
    val uri = Mms.CONTENT_URI
    val projection = arrayOf(
        Mms._ID,
        Mms.DATE,
        Mms.READ,
        Mms.MESSAGE_BOX,
        Mms.THREAD_ID,
        Mms.SUBSCRIPTION_ID,
        Mms.STATUS
    )

    var selection: String? = null
    var selectionArgs: Array<String>? = null

    if (threadId == null && dateFrom != -1) {
        selection = "${Sms.DATE} < ${dateFrom.toLong()}" //Should not multiply 1000 here, because date in mms's database is different from sms's.
    } else if (threadId != null && dateFrom == -1) {
        selection = "${Sms.THREAD_ID} = ?"
        selectionArgs = arrayOf(threadId.toString())
    } else if (threadId != null) {
        selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} < ${dateFrom.toLong()}"
        selectionArgs = arrayOf(threadId.toString())
    }

    val messages = ArrayList<Message>()
    val contactsMap = HashMap<Int, SimpleContact>()
    val threadParticipants = HashMap<Long, ArrayList<SimpleContact>>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val mmsId = cursor.getLongValue(Mms._ID)
        val type = cursor.getIntValue(Mms.MESSAGE_BOX)
        val date = cursor.getLongValue(Mms.DATE).toInt()
        val read = cursor.getIntValue(Mms.READ) == 1
        val threadId = cursor.getLongValue(Mms.THREAD_ID)
        val subscriptionId = cursor.getIntValue(Mms.SUBSCRIPTION_ID)
        val status = cursor.getIntValue(Mms.STATUS)
        val participants = if (threadParticipants.containsKey(threadId)) {
            threadParticipants[threadId]!!
        } else {
            val parts = getThreadParticipants(threadId, contactsMap)
            threadParticipants[threadId] = parts
            parts
        }

        val isMMS = true
        val attachment = getMmsAttachment(mmsId, getImageResolutions)
        val body = attachment.text
        var senderNumber = ""
        var senderName = ""
        var senderPhotoUri = ""

        if (type != Mms.MESSAGE_BOX_SENT && type != Mms.MESSAGE_BOX_FAILED) {
            senderNumber = getMMSSender(mmsId)
            val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
            senderName = namePhoto.name
            senderPhotoUri = namePhoto.photoUri ?: ""
        }

        val message =
            Message(
                mmsId,
                body,
                type,
                status,
                participants,
                date,
                read,
                threadId,
                isMMS,
                attachment,
                senderNumber,
                senderName,
                senderPhotoUri,
                subscriptionId
            )
        messages.add(message)

        participants.forEach {
            contactsMap[it.rawId] = it
        }
    }

    return messages
}

fun Context.getMMSSender(msgId: Long): String {
    val uri = Uri.parse("${Mms.CONTENT_URI}/$msgId/addr")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (ignored: Exception) {
    }
    return ""
}

fun Context.getConversations(threadId: Long? = null, privateContacts: ArrayList<SimpleContact> = ArrayList()): ArrayList<Conversation> {
    val archiveAvailable = config.isArchiveAvailable

    val uri = Uri.parse("${Threads.CONTENT_URI}?simple=true")
    val projection = mutableListOf(
        Threads._ID,
        Threads.SNIPPET,
        Threads.DATE,
        Threads.READ,
        Threads.RECIPIENT_IDS,
    )

    if (archiveAvailable) {
        projection += Threads.ARCHIVED
    }

    var selection = "${Threads.MESSAGE_COUNT} > ?"
    var selectionArgs = arrayOf("0")
    if (threadId != null) {
        selection += " AND ${Threads._ID} = ?"
        selectionArgs = arrayOf("0", threadId.toString())
    }

    val sortOrder = "${Threads.DATE} DESC"

    val conversations = ArrayList<Conversation>()
    val simpleContactHelper = SimpleContactsHelper(this)
    val blockedNumbers = getBlockedNumbers()
    try {
        queryCursorUnsafe(uri, projection.toTypedArray(), selection, selectionArgs, sortOrder) { cursor ->
            val id = cursor.getLongValue(Threads._ID)
            var snippet = cursor.getStringValue(Threads.SNIPPET) ?: ""
            if (snippet.isEmpty()) {
                snippet = getThreadSnippet(id)
            }

            var date = cursor.getLongValue(Threads.DATE)
            if (date.toString().length > 10) {
                date /= 1000
            }

            val rawIds = cursor.getStringValue(Threads.RECIPIENT_IDS)
            val recipientIds = rawIds.split(" ").filter { it.areDigitsOnly() }.map { it.toInt() }.toMutableList()
            val phoneNumbers = getThreadPhoneNumbers(recipientIds)
            if (phoneNumbers.isEmpty() || phoneNumbers.any { isNumberBlocked(it, blockedNumbers) }) {
                return@queryCursorUnsafe
            }

            val names = getThreadContactNames(phoneNumbers, privateContacts)
            val title = TextUtils.join(", ", names.toTypedArray())
            val photoUri = ""
            val isGroupConversation = phoneNumbers.size > 1
            val read = cursor.getIntValue(Threads.READ) == 1
            val archived = if (archiveAvailable) cursor.getIntValue(Threads.ARCHIVED) == 1 else false
            val conversation = Conversation(id, snippet, date.toInt(), read, title, photoUri, isGroupConversation, phoneNumbers.first(), isArchived = archived)
            conversations.add(conversation)
        }
    } catch (sqliteException: SQLiteException) {
        if (sqliteException.message?.contains("no such column: archived") == true && archiveAvailable) {
            config.isArchiveAvailable = false
            return getConversations(threadId, privateContacts)
        } else {
            showErrorToast(sqliteException)
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }

    conversations.sortByDescending { it.date }
    return conversations
}

private fun Context.queryCursorUnsafe(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    callback: (cursor: Cursor) -> Unit
) {
    val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    cursor?.use {
        if (cursor.moveToFirst()) {
            do {
                callback(cursor)
            } while (cursor.moveToNext())
        }
    }
}

fun Context.getConversationIds(): List<Long> {
    val uri = Uri.parse("${Threads.CONTENT_URI}?simple=true")
    val projection = arrayOf(Threads._ID)
    val selection = "${Threads.MESSAGE_COUNT} > ?"
    val selectionArgs = arrayOf("0")
    val sortOrder = "${Threads.DATE} ASC"
    val conversationIds = mutableListOf<Long>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
        val id = cursor.getLongValue(Threads._ID)
        conversationIds.add(id)
    }
    return conversationIds
}

// based on https://stackoverflow.com/a/6446831/1967672
@SuppressLint("NewApi")
fun Context.getMmsAttachment(id: Long, getImageResolutions: Boolean): MessageAttachment {
    val uri = if (isQPlus()) {
        Mms.Part.CONTENT_URI
    } else {
        Uri.parse("content://mms/part")
    }

    val projection = arrayOf(
        Mms._ID,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.TEXT
    )
    val selection = "${Mms.Part.MSG_ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    val messageAttachment = MessageAttachment(id, "", arrayListOf())

    var attachmentNames: List<String>? = null
    var attachmentCount = 0
    queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
        val partId = cursor.getLongValue(Mms._ID)
        val mimetype = cursor.getStringValue(Mms.Part.CONTENT_TYPE)
        if (mimetype == "text/plain") {
            messageAttachment.text = cursor.getStringValue(Mms.Part.TEXT) ?: ""
        } else if (mimetype.startsWith("image/") || mimetype.startsWith("video/")) {
            val fileUri = Uri.withAppendedPath(uri, partId.toString())
            var width = 0
            var height = 0

            if (getImageResolutions) {
                try {
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeStream(contentResolver.openInputStream(fileUri), null, options)
                    width = options.outWidth
                    height = options.outHeight
                } catch (e: Exception) {
                }
            }

            val attachment = Attachment(partId, id, fileUri.toString(), mimetype, width, height, "")
            messageAttachment.attachments.add(attachment)
        } else if (mimetype != "application/smil") {
            val attachmentName = attachmentNames?.getOrNull(attachmentCount) ?: ""
            val attachment = Attachment(partId, id, Uri.withAppendedPath(uri, partId.toString()).toString(), mimetype, 0, 0, attachmentName)
            messageAttachment.attachments.add(attachment)
            attachmentCount++
        }
    }

    return messageAttachment
}

fun Context.getLatestMMS(): Message? {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    return getMMS(sortOrder = sortOrder).firstOrNull()
}

fun Context.getThreadSnippet(threadId: Long): String {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    val latestMms = getMMS(threadId, false, sortOrder).firstOrNull()
    var snippet = latestMms?.body ?: ""

    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.BODY
    )

    val selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} > ?"
    val selectionArgs = arrayOf(
        threadId.toString(),
        latestMms?.date?.toString() ?: "0"
    )
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        cursor?.use {
            if (cursor.moveToFirst()) {
                snippet = cursor.getStringValue(Sms.BODY)
            }
        }
    } catch (ignored: Exception) {
    }
    return snippet
}

fun Context.getMessageRecipientAddress(messageId: Long): String {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(messageId.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Sms.ADDRESS)
            }
        }
    } catch (e: Exception) {
    }

    return ""
}

fun Context.getThreadParticipants(threadId: Long, contactsMap: HashMap<Int, SimpleContact>?): ArrayList<SimpleContact> {
    val uri = Uri.parse("${MmsSms.CONTENT_CONVERSATIONS_URI}?simple=true")
    val projection = arrayOf(
        ThreadsColumns.RECIPIENT_IDS
    )
    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    val participants = ArrayList<SimpleContact>()
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val address = cursor.getStringValue(ThreadsColumns.RECIPIENT_IDS)
                address.split(" ").filter { it.areDigitsOnly() }.forEach {
                    val addressId = it.toInt()
                    if (contactsMap?.containsKey(addressId) == true) {
                        participants.add(contactsMap[addressId]!!)
                        return@forEach
                    }

                    val number = getPhoneNumberFromAddressId(addressId)
                    val namePhoto = getNameAndPhotoFromPhoneNumber(number)
                    val name = namePhoto.name
                    val photoUri = namePhoto.photoUri ?: ""
                    val phoneNumber = PhoneNumber(number, 0, "", number)
                    val contact = SimpleContact(addressId, addressId, name, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
                    participants.add(contact)
                }
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return participants
}

fun Context.getThreadPhoneNumbers(recipientIds: List<Int>): ArrayList<String> {
    val numbers = ArrayList<String>()
    recipientIds.forEach {
        numbers.add(getPhoneNumberFromAddressId(it))
    }
    return numbers
}

fun Context.getThreadContactNames(phoneNumbers: List<String>, privateContacts: ArrayList<SimpleContact>): ArrayList<String> {
    val names = ArrayList<String>()
    phoneNumbers.forEach { number ->
        val name = SimpleContactsHelper(this).getNameFromPhoneNumber(number)
        if (name != number) {
            names.add(name)
        } else {
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) }
            if (privateContact == null) {
                names.add(name)
            } else {
                names.add(privateContact.name)
            }
        }
    }
    return names
}

fun Context.getPhoneNumberFromAddressId(canonicalAddressId: Int): String {
    val uri = Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(canonicalAddressId.toString())
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return ""
}

fun Context.getSuggestedContacts(privateContacts: ArrayList<SimpleContact>): ArrayList<SimpleContact> {
    val contacts = ArrayList<SimpleContact>()
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val sortOrder = "${Sms.DATE} DESC LIMIT 50"
    val blockedNumbers = getBlockedNumbers()

    queryCursor(uri, projection, null, null, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        var senderName = namePhoto.name
        var photoUri = namePhoto.photoUri ?: ""
        if (isNumberBlocked(senderNumber, blockedNumbers)) {
            return@queryCursor
        } else if (namePhoto.name == senderNumber) {
            if (privateContacts.isNotEmpty()) {
                val privateContact = privateContacts.firstOrNull { it.phoneNumbers.first().normalizedNumber == senderNumber }
                if (privateContact != null) {
                    senderName = privateContact.name
                    photoUri = privateContact.photoUri
                } else {
                    return@queryCursor
                }
            } else {
                return@queryCursor
            }
        }

        val phoneNumber = PhoneNumber(senderNumber, 0, "", senderNumber)
        val contact = SimpleContact(0, 0, senderName, photoUri, arrayListOf(phoneNumber), ArrayList(), ArrayList())
        if (!contacts.map { it.phoneNumbers.first().normalizedNumber.trimToComparableNumber() }.contains(senderNumber.trimToComparableNumber())) {
            contacts.add(contact)
        }
    }

    return contacts
}

fun Context.getNameAndPhotoFromPhoneNumber(number: String): NamePhoto {
    if (!hasPermission(PERMISSION_READ_CONTACTS)) {
        return NamePhoto(number, null)
    }

    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val projection = arrayOf(
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup.PHOTO_URI
    )

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                val name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValue(PhoneLookup.PHOTO_URI)
                return NamePhoto(name, photoUri)
            }
        }
    } catch (e: Exception) {
    }

    return NamePhoto(number, null)
}

fun Context.insertNewSMS(
    address: String,
    subject: String,
    body: String,
    date: Long,
    read: Int,
    threadId: Long,
    type: Int,
    subscriptionId: Int
): Long {
    val uri = Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.ADDRESS, address)
        put(Sms.SUBJECT, subject)
        put(Sms.BODY, body)
        put(Sms.DATE, date)
        put(Sms.READ, read)
        put(Sms.THREAD_ID, threadId)
        put(Sms.TYPE, type)
        put(Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return try {
        val newUri = contentResolver.insert(uri, contentValues)
        newUri?.lastPathSegment?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    }
}
fun Context.markThreadMessagesUnread(threadId: Long) {
    arrayOf(Sms.CONTENT_URI, Mms.CONTENT_URI).forEach { uri ->
        val contentValues = ContentValues().apply {
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
        }
        val selection = "${Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())
        contentResolver.update(uri, contentValues, selection, selectionArgs)
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(address: String): Long {
    return try {
        Threads.getOrCreateThreadId(this, address)
    } catch (e: Exception) {
        0L
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(addresses: Set<String>): Long {
    return try {
        Threads.getOrCreateThreadId(this, addresses)
    } catch (e: Exception) {
        0L
    }
}

fun Context.getNameFromAddress(address: String, privateCursor: Cursor?): String {
    var sender = getNameAndPhotoFromPhoneNumber(address).name
    if (address == sender) {
        val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
        sender = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }?.name ?: address
    }
    return sender
}

fun Context.getContactFromAddress(address: String, callback: ((contact: SimpleContact?) -> Unit)) {
    val privateCursor = getMyContactsCursor(false, true)
    SimpleContactsHelper(this).getAvailableContacts(false) {
        val contact = it.firstOrNull { it.doesHavePhoneNumber(address) }
        if (contact == null) {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }
            callback(privateContact)
        } else {
            callback(contact)
        }
    }
}

fun Context.removeDiacriticsIfNeeded(text: String): String {
    return if (config.useSimpleCharacters) text.normalizeString() else text
}

fun Context.getSmsDraft(threadId: Long): String? {
    val uri = Sms.Draft.CONTENT_URI
    val projection = arrayOf(Sms.BODY)
    val selection = "${Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                return cursor.getString(0)
            }
        }
    } catch (e: Exception) {
    }

    return null
}

fun Context.getAllDrafts(): HashMap<Long, String?> {
    val drafts = HashMap<Long, String?>()
    val uri = Sms.Draft.CONTENT_URI
    val projection = arrayOf(Sms.BODY, Sms.THREAD_ID)

    try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val threadId = it.getLongValue(Sms.THREAD_ID)
                val draft = it.getStringValue(Sms.BODY)
                if (draft != null) {
                    drafts[threadId] = draft
                }
            }
        }
    } catch (e: Exception) {
    }

    return drafts
}

fun Context.saveSmsDraft(body: String, threadId: Long) {
    val uri = Sms.Draft.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.BODY, body)
        put(Sms.DATE, System.currentTimeMillis().toString())
        put(Sms.TYPE, Sms.MESSAGE_TYPE_DRAFT)
        put(Sms.THREAD_ID, threadId)
    }

    try {
        contentResolver.insert(uri, contentValues)
    } catch (e: Exception) {
    }
}

fun Context.deleteSmsDraft(threadId: Long) {
    val uri = Sms.Draft.CONTENT_URI
    val projection = arrayOf(Sms._ID)
    val selection = "${Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                val draftId = cursor.getLong(0)
                val draftUri = Uri.withAppendedPath(Sms.CONTENT_URI, "/${draftId}")
                contentResolver.delete(draftUri, null, null)
            }
        }
    } catch (e: Exception) {
    }
}


fun Context.getFileSizeFromUri(uri: Uri): Long {
    val assetFileDescriptor = try {
        contentResolver.openAssetFileDescriptor(uri, "r")
    } catch (e: FileNotFoundException) {
        null
    }

    // uses ParcelFileDescriptor#getStatSize underneath if failed
    val length = assetFileDescriptor?.use { it.length } ?: FILE_SIZE_NONE
    if (length != -1L) {
        return length
    }

    // if "content://" uri scheme, try contentResolver table
    if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT)) {
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            // maybe shouldn't trust ContentResolver for size:
            // https://stackoverflow.com/questions/48302972/content-resolver-returns-wrong-size
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex == -1) {
                return@use FILE_SIZE_NONE
            }
            cursor.moveToFirst()
            return try {
                cursor.getLong(sizeIndex)
            } catch (_: Throwable) {
                FILE_SIZE_NONE
            }
        } ?: FILE_SIZE_NONE
    } else {
        return FILE_SIZE_NONE
    }
}


fun Context.subscriptionManagerCompat(): SubscriptionManager {
    return getSystemService(SubscriptionManager::class.java)
}
