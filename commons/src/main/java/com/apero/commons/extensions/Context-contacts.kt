package com.apero.commons.extensions

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import com.helpers.ContactsHelper
import com.simplemobiletools.commons.helpers.DEFAULT_MIMETYPE
import com.simplemobiletools.commons.helpers.PERMISSION_READ_CONTACTS
import com.simplemobiletools.commons.helpers.PERMISSION_WRITE_CONTACTS
import com.simplemobiletools.commons.helpers.SMT_PRIVATE
import com.simplemobiletools.commons.helpers.SOCIAL_MESSAGE
import com.simplemobiletools.commons.helpers.SOCIAL_VIDEO_CALL
import com.simplemobiletools.commons.helpers.SOCIAL_VOICE_CALL
import com.simplemobiletools.commons.helpers.SimpleContactsHelper
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.models.contacts.Contact
import com.simplemobiletools.commons.models.contacts.ContactSource
import com.simplemobiletools.commons.models.contacts.Organization
import com.simplemobiletools.commons.models.contacts.SocialAction
import java.io.File

fun Context.getEmptyContact(): Contact {
    val originalContactSource = if (hasContactPermissions()) baseConfig.lastUsedContactSource else SMT_PRIVATE
    val organization = Organization("", "")
    return Contact(
        0, "", "", "", "", "", "", "", ArrayList(), ArrayList(), ArrayList(), ArrayList(), originalContactSource, 0, 0, "",
        null, "", ArrayList(), organization, ArrayList(), ArrayList(), DEFAULT_MIMETYPE, null
    )
}

fun Context.sendAddressIntent(address: String) {
    val location = Uri.encode(address)
    val uri = Uri.parse("geo:0,0?q=$location")

    Intent(Intent.ACTION_VIEW, uri).apply {
        launchActivityIntent(this)
    }
}

fun Context.openWebsiteIntent(url: String) {
    val website = if (url.startsWith("http")) {
        url
    } else {
        "https://$url"
    }

    Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(website)
        launchActivityIntent(this)
    }
}

fun Context.getLookupUriRawId(dataUri: Uri): Int {
    val lookupKey = getLookupKeyFromUri(dataUri)
    if (lookupKey != null) {
        val uri = lookupContactUri(lookupKey, this)
        if (uri != null) {
            return getContactUriRawId(uri)
        }
    }
    return -1
}

fun Context.getContactUriRawId(uri: Uri): Int {
    val projection = arrayOf(ContactsContract.Contacts.NAME_RAW_CONTACT_ID)
    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, null, null, null)
        if (cursor!!.moveToFirst()) {
            return cursor.getIntValue(ContactsContract.Contacts.NAME_RAW_CONTACT_ID)
        }
    } catch (ignored: Exception) {
    } finally {
        cursor?.close()
    }
    return -1
}

// from https://android.googlesource.com/platform/packages/apps/Dialer/+/68038172793ee0e2ab3e2e56ddfbeb82879d1f58/java/com/android/contacts/common/util/UriUtils.java
fun getLookupKeyFromUri(lookupUri: Uri): String? {
    return if (!isEncodedContactUri(lookupUri)) {
        val segments = lookupUri.pathSegments
        if (segments.size < 3) null else Uri.encode(segments[2])
    } else {
        null
    }
}

fun isEncodedContactUri(uri: Uri?): Boolean {
    if (uri == null) {
        return false
    }
    val lastPathSegment = uri.lastPathSegment ?: return false
    return lastPathSegment == "encoded"
}

fun lookupContactUri(lookup: String, context: Context): Uri? {
    val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
    return try {
        ContactsContract.Contacts.lookupContact(context.contentResolver, lookupUri)
    } catch (e: Exception) {
        null
    }
}

fun Context.getCachePhoto(): File {
    val imagesFolder = File(cacheDir, "my_cache")
    if (!imagesFolder.exists()) {
        imagesFolder.mkdirs()
    }

    val file = File(imagesFolder, "Photo_${System.currentTimeMillis()}.jpg")
    file.createNewFile()
    return file
}

fun Context.getPhotoThumbnailSize(): Int {
    val uri = ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI
    val projection = arrayOf(ContactsContract.DisplayPhoto.THUMBNAIL_MAX_DIM)
    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, projection, null, null, null)
        if (cursor?.moveToFirst() == true) {
            return cursor.getIntValue(ContactsContract.DisplayPhoto.THUMBNAIL_MAX_DIM)
        }
    } catch (ignored: Exception) {
    } finally {
        cursor?.close()
    }
    return 0
}

fun Context.hasContactPermissions() = hasPermission(PERMISSION_READ_CONTACTS) && hasPermission(PERMISSION_WRITE_CONTACTS)

fun Context.sendSMSToContacts(contacts: ArrayList<Contact>) {
    val numbers = StringBuilder()
    contacts.forEach {
        val number = it.phoneNumbers.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
            ?: it.phoneNumbers.firstOrNull()
        if (number != null) {
            numbers.append("${Uri.encode(number.value)};")
        }
    }

    val uriString = "smsto:${numbers.toString().trimEnd(';')}"
    Intent(Intent.ACTION_SENDTO, Uri.parse(uriString)).apply {
        launchActivityIntent(this)
    }
}

fun Context.sendEmailToContacts(contacts: ArrayList<Contact>) {
    val emails = ArrayList<String>()
    contacts.forEach {
        it.emails.forEach {
            if (it.value.isNotEmpty()) {
                emails.add(it.value)
            }
        }
    }

    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, emails.toTypedArray())
        launchActivityIntent(this)
    }
}


fun Context.getContactPublicUri(contact: Contact): Uri {
    val lookupKey = if (contact.isPrivate()) {
        "local_${contact.id}"
    } else {
        SimpleContactsHelper(this).getContactLookupKey(contact.id.toString())
    }
    return Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
}

fun Context.getVisibleContactSources(): ArrayList<String> {
    val sources = getAllContactSources()
    val ignoredContactSources = baseConfig.ignoredContactSources
    return ArrayList(sources).filter { !ignoredContactSources.contains(it.getFullIdentifier()) }
        .map { it.name }.toMutableList() as ArrayList<String>
}

fun Context.getAllContactSources(): ArrayList<ContactSource> {
    val sources = ContactsHelper(this).getDeviceContactSources()
    sources.add(getPrivateContactSource())
    return sources.toMutableList() as ArrayList<ContactSource>
}

fun Context.getPrivateContactSource() = ContactSource(SMT_PRIVATE, SMT_PRIVATE, "getString(R.string.phone_storage_hidden)")

fun Context.getSocialActions(id: Int): ArrayList<SocialAction> {
    val uri = ContactsContract.Data.CONTENT_URI
    val projection = arrayOf(
        ContactsContract.Data._ID,
        ContactsContract.Data.DATA3,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Data.ACCOUNT_TYPE_AND_DATA_SET
    )

    val socialActions = ArrayList<SocialAction>()
    var curActionId = 0
    val selection = "${ContactsContract.Data.RAW_CONTACT_ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    queryCursor(uri, projection, selection, selectionArgs, null, true) { cursor ->
        val mimetype = cursor.getStringValue(ContactsContract.Data.MIMETYPE)
        val type = when (mimetype) {
            // WhatsApp
            "vnd.android.cursor.item/vnd.com.whatsapp.profile" -> SOCIAL_MESSAGE
            "vnd.android.cursor.item/vnd.com.whatsapp.voip.call" -> SOCIAL_VOICE_CALL
            "vnd.android.cursor.item/vnd.com.whatsapp.video.call" -> SOCIAL_VIDEO_CALL

            // Viber
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_call" -> SOCIAL_VOICE_CALL
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_out_call_viber" -> SOCIAL_VOICE_CALL
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_out_call_none_viber" -> SOCIAL_VOICE_CALL
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_message" -> SOCIAL_MESSAGE

            // Signal
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact" -> SOCIAL_MESSAGE
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call" -> SOCIAL_VOICE_CALL

            // Telegram
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call" -> SOCIAL_VOICE_CALL
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call.video" -> SOCIAL_VIDEO_CALL
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile" -> SOCIAL_MESSAGE

            // Threema
            "vnd.android.cursor.item/vnd.ch.threema.app.profile" -> SOCIAL_MESSAGE
            "vnd.android.cursor.item/vnd.ch.threema.app.call" -> SOCIAL_VOICE_CALL
            else -> return@queryCursor
        }

        val label = cursor.getStringValue(ContactsContract.Data.DATA3)
        val realID = cursor.getLongValue(ContactsContract.Data._ID)
        val packageName = cursor.getStringValue(ContactsContract.Data.ACCOUNT_TYPE_AND_DATA_SET)
        val socialAction = SocialAction(curActionId++, type, label, mimetype, realID, packageName)
        socialActions.add(socialAction)
    }
    return socialActions
}


@TargetApi(Build.VERSION_CODES.N)
fun Context.blockContact(contact: Contact): Boolean {
    var contactBlocked = true
    ensureBackgroundThread {
        contact.phoneNumbers.forEach {
            val numberBlocked = addBlockedNumber(PhoneNumberUtils.stripSeparators(it.value))
            contactBlocked = contactBlocked && numberBlocked
        }
    }

    return contactBlocked
}

@TargetApi(Build.VERSION_CODES.N)
fun Context.unblockContact(contact: Contact): Boolean {
    var contactUnblocked = true
    ensureBackgroundThread {
        contact.phoneNumbers.forEach {
            val numberUnblocked = deleteBlockedNumber(PhoneNumberUtils.stripSeparators(it.value))
            contactUnblocked = contactUnblocked && numberUnblocked
        }
    }

    return contactUnblocked
}
