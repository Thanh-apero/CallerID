package com.apero.commons.dialer.helpers

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.AUTHORITY
import android.provider.ContactsContract.CALLER_IS_SYNCADAPTER
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings
import android.provider.MediaStore
import android.text.TextUtils
import android.util.SparseArray
import com.apero.commons.dialer.extensions.baseConfig
import com.apero.commons.dialer.extensions.getAllContactSources
import com.apero.commons.dialer.extensions.getIntValue
import com.apero.commons.dialer.extensions.getLongValue
import com.apero.commons.dialer.extensions.getPhotoThumbnailSize
import com.apero.commons.dialer.extensions.getPrivateContactSource
import com.apero.commons.dialer.extensions.getStringValue
import com.apero.commons.dialer.extensions.getStringValueOrNull
import com.apero.commons.dialer.extensions.getVisibleContactSources
import com.apero.commons.dialer.extensions.hasPermission
import com.apero.commons.dialer.extensions.normalizePhoneNumber
import com.apero.commons.dialer.extensions.queryCursor
import com.apero.commons.dialer.extensions.showErrorToast
import com.apero.commons.dialer.models.PhoneNumber
import com.apero.commons.dialer.models.contacts.Address
import com.apero.commons.dialer.models.contacts.Contact
import com.apero.commons.dialer.models.contacts.ContactSource
import com.apero.commons.dialer.models.contacts.Email
import com.apero.commons.dialer.models.contacts.Event
import com.apero.commons.dialer.models.contacts.Group
import com.apero.commons.dialer.models.contacts.IM
import com.apero.commons.dialer.models.contacts.Organization
import com.apero.commons.overloads.times
import com.apero.commons.dialer.extensions.getByteArray
import java.util.Locale

class ContactsHelper(val context: Context) {
    private val BATCH_SIZE = 50
    private var displayContactSources = ArrayList<String>()

    fun getContacts(
        getAll: Boolean = false,
        gettingDuplicates: Boolean = false,
        ignoredContactSources: HashSet<String> = HashSet(),
        showOnlyContactsWithNumbers: Boolean = context.baseConfig.showOnlyContactsWithNumbers,
        callback: (ArrayList<Contact>) -> Unit
    ) {
        ensureBackgroundThread {
            val contacts = SparseArray<Contact>()
            displayContactSources = context.getVisibleContactSources()

            if (getAll) {
                displayContactSources = if (ignoredContactSources.isEmpty()) {
                    context.getAllContactSources().map { it.name }.toMutableList() as ArrayList
                } else {
                    context.getAllContactSources().filter {
                        it.getFullIdentifier().isNotEmpty() && !ignoredContactSources.contains(it.getFullIdentifier())
                    }.map { it.name }.toMutableList() as ArrayList
                }
            }

            getDeviceContacts(contacts, ignoredContactSources, gettingDuplicates)

            val contactsSize = contacts.size()
            val tempContacts = ArrayList<Contact>(contactsSize)
            val resultContacts = ArrayList<Contact>(contactsSize)

            (0 until contactsSize).filter {
                if (ignoredContactSources.isEmpty() && showOnlyContactsWithNumbers) {
                    contacts.valueAt(it).phoneNumbers.isNotEmpty()
                } else {
                    true
                }
            }.mapTo(tempContacts) {
                contacts.valueAt(it)
            }

            if (context.baseConfig.mergeDuplicateContacts && ignoredContactSources.isEmpty() && !getAll) {
                tempContacts.filter { displayContactSources.contains(it.source) }.groupBy { it.getNameToDisplay().toLowerCase() }.values.forEach { it ->
                    if (it.size == 1) {
                        resultContacts.add(it.first())
                    } else {
                        val sorted = it.sortedByDescending { it.getStringToCompare().length }
                        resultContacts.add(sorted.first())
                    }
                }
            } else {
                resultContacts.addAll(tempContacts)
            }

            // groups are obtained with contactID, not rawID, so assign them to proper contacts like this
            val groups = getContactGroups(getStoredGroupsSync())
            val size = groups.size()
            for (i in 0 until size) {
                val key = groups.keyAt(i)
                resultContacts.firstOrNull { it.contactId == key }?.groups = groups.valueAt(i)
            }

            Contact.sorting = context.baseConfig.sorting
            Contact.startWithSurname = context.baseConfig.startNameWithSurname
            resultContacts.sort()

            Handler(Looper.getMainLooper()).post {
                callback(resultContacts)
            }
        }
    }

    private fun getContentResolverAccounts(): HashSet<ContactSource> {
        val sources = HashSet<ContactSource>()
        arrayOf(Groups.CONTENT_URI, Settings.CONTENT_URI, RawContacts.CONTENT_URI).forEach {
            fillSourcesFromUri(it, sources)
        }

        return sources
    }

    private fun fillSourcesFromUri(uri: Uri, sources: HashSet<ContactSource>) {
        val projection = arrayOf(
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE
        )

        context.queryCursor(uri, projection) { cursor ->
            val name = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
            val type = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""
            var publicName = name
            if (type == TELEGRAM_PACKAGE) {
                publicName = "telegram"
            }

            val source = ContactSource(name, type, publicName)
            sources.add(source)
        }
    }

    private fun getDeviceContacts(contacts: SparseArray<Contact>, ignoredContactSources: HashSet<String>?, gettingDuplicates: Boolean) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return
        }

        val ignoredSources = ignoredContactSources ?: context.baseConfig.ignoredContactSources
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        arrayOf(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).forEach { mimetype ->
            val selection = "${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(mimetype)
            val sortOrder = getSortString()

            context.queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val accountType = cursor.getStringValue(RawContacts.ACCOUNT_TYPE) ?: ""

                if (ignoredSources.contains("$accountName:$accountType")) {
                    return@queryCursor
                }

                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
                var prefix = ""
                var firstName = ""
                var middleName = ""
                var surname = ""
                var suffix = ""

                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    firstName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                }

                var photoUri = ""
                var starred = 0
                var contactId = 0
                var thumbnailUri = ""
                var ringtone: String? = null

                if (!gettingDuplicates) {
                    photoUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_URI) ?: ""
                    starred = cursor.getIntValue(CommonDataKinds.StructuredName.STARRED)
                    contactId = cursor.getIntValue(Data.CONTACT_ID)
                    thumbnailUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                    ringtone = cursor.getStringValue(CommonDataKinds.StructuredName.CUSTOM_RINGTONE)
                }

                val nickname = ""
                val numbers = ArrayList<PhoneNumber>()          // proper value is obtained below
                val emails = ArrayList<Email>()
                val addresses = ArrayList<Address>()
                val events = ArrayList<Event>()
                val notes = ""
                val groups = ArrayList<Group>()
                val organization = Organization("", "")
                val websites = ArrayList<String>()
                val ims = ArrayList<IM>()
                val contact = Contact(
                    id, prefix, firstName, middleName, surname, suffix, nickname, photoUri, numbers, emails, addresses,
                    events, accountName, starred, contactId, thumbnailUri, null, notes, groups, organization, websites, ims, mimetype, ringtone
                )

                contacts.put(id, contact)
            }
        }

        val emails = getEmails()
        var size = emails.size()
        for (i in 0 until size) {
            val key = emails.keyAt(i)
            contacts[key]?.emails = emails.valueAt(i)
        }

        val organizations = getOrganizations()
        size = organizations.size()
        for (i in 0 until size) {
            val key = organizations.keyAt(i)
            contacts[key]?.organization = organizations.valueAt(i)
        }

        // no need to fetch some fields if we are only getting duplicates of the current contact
        if (gettingDuplicates) {
            return
        }

        val phoneNumbers = getPhoneNumbers(null)
        size = phoneNumbers.size()
        for (i in 0 until size) {
            val key = phoneNumbers.keyAt(i)
            if (contacts[key] != null) {
                val numbers = phoneNumbers.valueAt(i)
                contacts[key].phoneNumbers = numbers
            }
        }

        val addresses = getAddresses()
        size = addresses.size()
        for (i in 0 until size) {
            val key = addresses.keyAt(i)
            contacts[key]?.addresses = addresses.valueAt(i)
        }

        val IMs = getIMs()
        size = IMs.size()
        for (i in 0 until size) {
            val key = IMs.keyAt(i)
            contacts[key]?.IMs = IMs.valueAt(i)
        }

        val events = getEvents()
        size = events.size()
        for (i in 0 until size) {
            val key = events.keyAt(i)
            contacts[key]?.events = events.valueAt(i)
        }

        val notes = getNotes()
        size = notes.size()
        for (i in 0 until size) {
            val key = notes.keyAt(i)
            contacts[key]?.notes = notes.valueAt(i)
        }

        val nicknames = getNicknames()
        size = nicknames.size()
        for (i in 0 until size) {
            val key = nicknames.keyAt(i)
            contacts[key]?.nickname = nicknames.valueAt(i)
        }

        val websites = getWebsites()
        size = websites.size()
        for (i in 0 until size) {
            val key = websites.keyAt(i)
            contacts[key]?.websites = websites.valueAt(i)
        }
    }

    private fun getPhoneNumbers(contactId: Int? = null): SparseArray<ArrayList<PhoneNumber>> {
        val phoneNumbers = SparseArray<ArrayList<PhoneNumber>>()
        val uri = CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Phone.NUMBER,
            CommonDataKinds.Phone.NORMALIZED_NUMBER,
            CommonDataKinds.Phone.TYPE,
            CommonDataKinds.Phone.LABEL,
            CommonDataKinds.Phone.IS_PRIMARY
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val number = cursor.getStringValue(CommonDataKinds.Phone.NUMBER) ?: return@queryCursor
            val normalizedNumber = cursor.getStringValue(CommonDataKinds.Phone.NORMALIZED_NUMBER) ?: number.normalizePhoneNumber()
            val type = cursor.getIntValue(CommonDataKinds.Phone.TYPE)
            val label = cursor.getStringValue(CommonDataKinds.Phone.LABEL) ?: ""
            val isPrimary = cursor.getIntValue(CommonDataKinds.Phone.IS_PRIMARY) != 0

            if (phoneNumbers[id] == null) {
                phoneNumbers.put(id, ArrayList())
            }

            val phoneNumber = PhoneNumber(number, type, label, normalizedNumber, isPrimary)
            phoneNumbers[id].add(phoneNumber)
        }

        return phoneNumbers
    }

    private fun getNicknames(contactId: Int? = null): SparseArray<String> {
        val nicknames = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Nickname.NAME
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val nickname = cursor.getStringValue(CommonDataKinds.Nickname.NAME) ?: return@queryCursor
            nicknames.put(id, nickname)
        }

        return nicknames
    }

    private fun getEmails(contactId: Int? = null): SparseArray<ArrayList<Email>> {
        val emails = SparseArray<ArrayList<Email>>()
        val uri = CommonDataKinds.Email.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Email.DATA,
            CommonDataKinds.Email.TYPE,
            CommonDataKinds.Email.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val email = cursor.getStringValue(CommonDataKinds.Email.DATA) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.Email.TYPE)
            val label = cursor.getStringValue(CommonDataKinds.Email.LABEL) ?: ""

            if (emails[id] == null) {
                emails.put(id, ArrayList())
            }

            emails[id]!!.add(Email(email, type, label))
        }

        return emails
    }

    private fun getAddresses(contactId: Int? = null): SparseArray<ArrayList<Address>> {
        val addresses = SparseArray<ArrayList<Address>>()
        val uri = CommonDataKinds.StructuredPostal.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            CommonDataKinds.StructuredPostal.TYPE,
            CommonDataKinds.StructuredPostal.LABEL
        )

        val selection = if (contactId == null) getSourcesSelection() else "${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = if (contactId == null) getSourcesSelectionArgs() else arrayOf(contactId.toString())

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val address = cursor.getStringValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.StructuredPostal.TYPE)
            val label = cursor.getStringValue(CommonDataKinds.StructuredPostal.LABEL) ?: ""

            if (addresses[id] == null) {
                addresses.put(id, ArrayList())
            }

            addresses[id]!!.add(Address(address, type, label))
        }

        return addresses
    }

    private fun getIMs(contactId: Int? = null): SparseArray<ArrayList<IM>> {
        val IMs = SparseArray<ArrayList<IM>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Im.DATA,
            CommonDataKinds.Im.PROTOCOL,
            CommonDataKinds.Im.CUSTOM_PROTOCOL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Im.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val IM = cursor.getStringValue(CommonDataKinds.Im.DATA) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.Im.PROTOCOL)
            val label = cursor.getStringValue(CommonDataKinds.Im.CUSTOM_PROTOCOL) ?: ""

            if (IMs[id] == null) {
                IMs.put(id, ArrayList())
            }

            IMs[id]!!.add(IM(IM, type, label))
        }

        return IMs
    }

    private fun getEvents(contactId: Int? = null): SparseArray<ArrayList<Event>> {
        val events = SparseArray<ArrayList<Event>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Event.START_DATE,
            CommonDataKinds.Event.TYPE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Event.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val startDate = cursor.getStringValue(CommonDataKinds.Event.START_DATE) ?: return@queryCursor
            val type = cursor.getIntValue(CommonDataKinds.Event.TYPE)

            if (events[id] == null) {
                events.put(id, ArrayList())
            }

            events[id]!!.add(Event(startDate, type))
        }

        return events
    }

    private fun getNotes(contactId: Int? = null): SparseArray<String> {
        val notes = SparseArray<String>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Note.NOTE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Note.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val note = cursor.getStringValue(CommonDataKinds.Note.NOTE) ?: return@queryCursor
            notes.put(id, note)
        }

        return notes
    }

    private fun getOrganizations(contactId: Int? = null): SparseArray<Organization> {
        val organizations = SparseArray<Organization>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Organization.COMPANY,
            CommonDataKinds.Organization.TITLE
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Organization.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val company = cursor.getStringValue(CommonDataKinds.Organization.COMPANY) ?: ""
            val title = cursor.getStringValue(CommonDataKinds.Organization.TITLE) ?: ""
            if (company.isEmpty() && title.isEmpty()) {
                return@queryCursor
            }

            val organization = Organization(company, title)
            organizations.put(id, organization)
        }

        return organizations
    }

    private fun getWebsites(contactId: Int? = null): SparseArray<ArrayList<String>> {
        val websites = SparseArray<ArrayList<String>>()
        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.RAW_CONTACT_ID,
            CommonDataKinds.Website.URL
        )

        val selection = getSourcesSelection(true, contactId != null)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.Website.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.RAW_CONTACT_ID)
            val url = cursor.getStringValue(CommonDataKinds.Website.URL) ?: return@queryCursor

            if (websites[id] == null) {
                websites.put(id, ArrayList())
            }

            websites[id]!!.add(url)
        }

        return websites
    }

    private fun getContactGroups(storedGroups: ArrayList<Group>, contactId: Int? = null): SparseArray<ArrayList<Group>> {
        val groups = SparseArray<ArrayList<Group>>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return groups
        }

        val uri = Data.CONTENT_URI
        val projection = arrayOf(
            Data.CONTACT_ID,
            Data.DATA1
        )

        val selection = getSourcesSelection(true, contactId != null, false)
        val selectionArgs = getSourcesSelectionArgs(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, contactId)

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getIntValue(Data.CONTACT_ID)
            val newRowId = cursor.getLongValue(Data.DATA1)

            val groupTitle = storedGroups.firstOrNull { it.id == newRowId }?.title ?: return@queryCursor
            val group = Group(newRowId, groupTitle)
            if (groups[id] == null) {
                groups.put(id, ArrayList())
            }
            groups[id]!!.add(group)
        }

        return groups
    }

    private fun getQuestionMarks() = ("?," * displayContactSources.filter { it.isNotEmpty() }.size).trimEnd(',')

    private fun getSourcesSelection(addMimeType: Boolean = false, addContactId: Boolean = false, useRawContactId: Boolean = true): String {
        val strings = ArrayList<String>()
        if (addMimeType) {
            strings.add("${Data.MIMETYPE} = ?")
        }

        if (addContactId) {
            strings.add("${if (useRawContactId) Data.RAW_CONTACT_ID else Data.CONTACT_ID} = ?")
        } else {
            // sometimes local device storage has null account_name, handle it properly
            val accountnameString = StringBuilder()
            if (displayContactSources.contains("")) {
                accountnameString.append("(")
            }
            accountnameString.append("${RawContacts.ACCOUNT_NAME} IN (${getQuestionMarks()})")
            if (displayContactSources.contains("")) {
                accountnameString.append(" OR ${RawContacts.ACCOUNT_NAME} IS NULL)")
            }
            strings.add(accountnameString.toString())
        }

        return TextUtils.join(" AND ", strings)
    }

    private fun getSourcesSelectionArgs(mimetype: String? = null, contactId: Int? = null): Array<String> {
        val args = ArrayList<String>()

        if (mimetype != null) {
            args.add(mimetype)
        }

        if (contactId != null) {
            args.add(contactId.toString())
        } else {
            args.addAll(displayContactSources.filter { it.isNotEmpty() })
        }

        return args.toTypedArray()
    }

    fun getStoredGroups(callback: (ArrayList<Group>) -> Unit) {
        ensureBackgroundThread {
            val groups = getStoredGroupsSync()
            Handler(Looper.getMainLooper()).post {
                callback(groups)
            }
        }
    }

    fun getStoredGroupsSync(): ArrayList<Group> {
        val groups = getDeviceStoredGroups()
        return groups
    }

    private fun getDeviceStoredGroups(): ArrayList<Group> {
        val groups = ArrayList<Group>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return groups
        }

        val uri = Groups.CONTENT_URI
        val projection = arrayOf(
            Groups._ID,
            Groups.TITLE,
            Groups.SYSTEM_ID
        )

        val selection = "${Groups.AUTO_ADD} = ? AND ${Groups.FAVORITES} = ?"
        val selectionArgs = arrayOf("0", "0")

        context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) { cursor ->
            val id = cursor.getLongValue(Groups._ID)
            val title = cursor.getStringValue(Groups.TITLE) ?: return@queryCursor

            val systemId = cursor.getStringValue(Groups.SYSTEM_ID)
            if (groups.map { it.title }.contains(title) && systemId != null) {
                return@queryCursor
            }

            groups.add(Group(id, title))
        }
        return groups
    }


    fun renameGroup(group: Group) {
        val operations = ArrayList<ContentProviderOperation>()
        ContentProviderOperation.newUpdate(Groups.CONTENT_URI).apply {
            val selection = "${Groups._ID} = ?"
            val selectionArgs = arrayOf(group.id.toString())
            withSelection(selection, selectionArgs)
            withValue(Groups.TITLE, group.title)
            operations.add(build())
        }

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun deleteGroup(id: Long) {
        val operations = ArrayList<ContentProviderOperation>()
        val uri = ContentUris.withAppendedId(Groups.CONTENT_URI, id).buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .build()

        operations.add(ContentProviderOperation.newDelete(uri).build())

        try {
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun getContactFromUri(uri: Uri): Contact? {
        val key = getLookupKeyFromUri(uri) ?: return null
        return getContactWithLookupKey(key)
    }

    private fun getLookupKeyFromUri(lookupUri: Uri): String? {
        val projection = arrayOf(ContactsContract.Contacts.LOOKUP_KEY)
        val cursor = context.contentResolver.query(lookupUri, projection, null, null, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(ContactsContract.Contacts.LOOKUP_KEY)
            }
        }
        return null
    }

    fun getContactWithLookupKey(key: String): Contact? {
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.LOOKUP_KEY} = ?"
        val selectionArgs = arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, key)
        return parseContactCursor(selection, selectionArgs)
    }

    private fun parseContactCursor(selection: String, selectionArgs: Array<String>): Contact? {
        val storedGroups = getStoredGroupsSync()
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                val id = cursor.getIntValue(Data.RAW_CONTACT_ID)

                var prefix = ""
                var firstName = ""
                var middleName = ""
                var surname = ""
                var suffix = ""
                var mimetype = cursor.getStringValue(Data.MIMETYPE)

                // if first line is an Organization type contact, go to next line if available
                if (mimetype != CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    if (!cursor.isLast() && cursor.moveToNext()) {
                        mimetype = cursor.getStringValue(Data.MIMETYPE)
                    }
                }
                // ignore names at Organization type contacts
                if (mimetype == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
                    prefix = cursor.getStringValue(CommonDataKinds.StructuredName.PREFIX) ?: ""
                    firstName = cursor.getStringValue(CommonDataKinds.StructuredName.GIVEN_NAME) ?: ""
                    middleName = cursor.getStringValue(CommonDataKinds.StructuredName.MIDDLE_NAME) ?: ""
                    surname = cursor.getStringValue(CommonDataKinds.StructuredName.FAMILY_NAME) ?: ""
                    suffix = cursor.getStringValue(CommonDataKinds.StructuredName.SUFFIX) ?: ""
                }

                val nickname = getNicknames(id)[id] ?: ""
                val photoUri = cursor.getStringValueOrNull(CommonDataKinds.Phone.PHOTO_URI) ?: ""
                val number = getPhoneNumbers(id)[id] ?: ArrayList()
                val emails = getEmails(id)[id] ?: ArrayList()
                val addresses = getAddresses(id)[id] ?: ArrayList()
                val events = getEvents(id)[id] ?: ArrayList()
                val notes = getNotes(id)[id] ?: ""
                val accountName = cursor.getStringValue(RawContacts.ACCOUNT_NAME) ?: ""
                val starred = cursor.getIntValue(CommonDataKinds.StructuredName.STARRED)
                val ringtone = cursor.getStringValue(CommonDataKinds.StructuredName.CUSTOM_RINGTONE)
                val contactId = cursor.getIntValue(Data.CONTACT_ID)
                val groups = getContactGroups(storedGroups, contactId)[contactId] ?: ArrayList()
                val thumbnailUri = cursor.getStringValue(CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI) ?: ""
                val organization = getOrganizations(id)[id] ?: Organization("", "")
                val websites = getWebsites(id)[id] ?: ArrayList()
                val ims = getIMs(id)[id] ?: ArrayList()
                return Contact(
                    id, prefix, firstName, middleName, surname, suffix, nickname, photoUri, number, emails, addresses, events,
                    accountName, starred, contactId, thumbnailUri, null, notes, groups, organization, websites, ims, mimetype, ringtone
                )
            }
        }

        return null
    }

    fun getContactSources(callback: (ArrayList<ContactSource>) -> Unit) {
        ensureBackgroundThread {
            callback(getContactSourcesSync())
        }
    }

    private fun getContactSourcesSync(): ArrayList<ContactSource> {
        val sources = getDeviceContactSources()
        sources.add(context.getPrivateContactSource())
        return ArrayList(sources)
    }

    fun getSaveableContactSources(callback: (ArrayList<ContactSource>) -> Unit) {
        ensureBackgroundThread {
            val ignoredTypes = arrayListOf(
                SIGNAL_PACKAGE,
                TELEGRAM_PACKAGE,
                WHATSAPP_PACKAGE,
                THREEMA_PACKAGE
            )

            val contactSources = getContactSourcesSync()
            val filteredSources = contactSources.filter { !ignoredTypes.contains(it.type) }.toMutableList() as ArrayList<ContactSource>
            callback(filteredSources)
        }
    }

    fun getDeviceContactSources(): LinkedHashSet<ContactSource> {
        val sources = LinkedHashSet<ContactSource>()
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return sources
        }

        if (!context.baseConfig.wasLocalAccountInitialized) {
            initializeLocalPhoneAccount()
            context.baseConfig.wasLocalAccountInitialized = true
        }

        val accounts = AccountManager.get(context).accounts

        if (context.hasPermission(PERMISSION_READ_SYNC_SETTINGS)) {
            accounts.forEach {
                if (ContentResolver.getIsSyncable(it, AUTHORITY) == 1) {
                    var publicName = it.name
                    if (it.type == TELEGRAM_PACKAGE) {
                        publicName = "telegram"
                    } else if (it.type == VIBER_PACKAGE) {
                    }
                    val contactSource = ContactSource(it.name, it.type, publicName)
                    sources.add(contactSource)
                }
            }
        }

        var hadEmptyAccount = false
        val allAccounts = getContentResolverAccounts()
        val contentResolverAccounts = allAccounts.filter {
            if (it.name.isEmpty() && it.type.isEmpty() && allAccounts.none { it.name.lowercase(Locale.getDefault()) == "phone" }) {
                hadEmptyAccount = true
            }

            it.name.isNotEmpty() && it.type.isNotEmpty() && !accounts.contains(Account(it.name, it.type))
        }
        sources.addAll(contentResolverAccounts)

        if (hadEmptyAccount) {
        }

        return sources
    }

    // make sure the local Phone contact source is initialized and available
    // https://stackoverflow.com/a/6096508/1967672
    private fun initializeLocalPhoneAccount() {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI).apply {
                withValue(RawContacts.ACCOUNT_NAME, null)
                withValue(RawContacts.ACCOUNT_TYPE, null)
                operations.add(build())
            }

            val results = context.contentResolver.applyBatch(AUTHORITY, operations)
            val rawContactUri = results.firstOrNull()?.uri ?: return
            context.contentResolver.delete(rawContactUri, null, null)
        } catch (ignored: Exception) {
        }
    }

    private fun getContactSourceType(accountName: String) = getDeviceContactSources().firstOrNull { it.name == accountName }?.type ?: ""

    private fun getContactProjection() = arrayOf(
        Data.MIMETYPE,
        Data.CONTACT_ID,
        Data.RAW_CONTACT_ID,
        CommonDataKinds.StructuredName.PREFIX,
        CommonDataKinds.StructuredName.GIVEN_NAME,
        CommonDataKinds.StructuredName.MIDDLE_NAME,
        CommonDataKinds.StructuredName.FAMILY_NAME,
        CommonDataKinds.StructuredName.SUFFIX,
        CommonDataKinds.StructuredName.PHOTO_URI,
        CommonDataKinds.StructuredName.PHOTO_THUMBNAIL_URI,
        CommonDataKinds.StructuredName.STARRED,
        CommonDataKinds.StructuredName.CUSTOM_RINGTONE,
        RawContacts.ACCOUNT_NAME,
        RawContacts.ACCOUNT_TYPE
    )

    private fun getSortString(): String {
        val sorting = context.baseConfig.sorting
        return when {
            sorting and SORT_BY_FIRST_NAME != 0 -> "${CommonDataKinds.StructuredName.GIVEN_NAME} COLLATE NOCASE"
            sorting and SORT_BY_MIDDLE_NAME != 0 -> "${CommonDataKinds.StructuredName.MIDDLE_NAME} COLLATE NOCASE"
            sorting and SORT_BY_SURNAME != 0 -> "${CommonDataKinds.StructuredName.FAMILY_NAME} COLLATE NOCASE"
            sorting and SORT_BY_FULL_NAME != 0 -> CommonDataKinds.StructuredName.DISPLAY_NAME
            else -> Data.RAW_CONTACT_ID
        }
    }

    private fun getRealContactId(id: Long): Int {
        val uri = Data.CONTENT_URI
        val projection = getContactProjection()
        val selection = "(${Data.MIMETYPE} = ? OR ${Data.MIMETYPE} = ?) AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CommonDataKinds.Organization.CONTENT_ITEM_TYPE, id.toString())

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getIntValue(Data.CONTACT_ID)
            }
        }

        return 0
    }

    private fun addPhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        if (contact.photoUri.isNotEmpty()) {
            val photoUri = Uri.parse(contact.photoUri)
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, photoUri)

            val thumbnailSize = context.getPhotoThumbnailSize()
            val scaledPhoto = Bitmap.createScaledBitmap(bitmap, thumbnailSize, thumbnailSize, false)
            val scaledSizePhotoData = scaledPhoto.getByteArray()
            scaledPhoto.recycle()

            val fullSizePhotoData = bitmap.getByteArray()
            bitmap.recycle()

            ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                withValue(Data.RAW_CONTACT_ID, contact.id)
                withValue(Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                withValue(CommonDataKinds.Photo.PHOTO, scaledSizePhotoData)
                operations.add(build())
            }

            addFullSizePhoto(contact.id.toLong(), fullSizePhotoData)
        }
        return operations
    }

    private fun removePhoto(contact: Contact, operations: ArrayList<ContentProviderOperation>): ArrayList<ContentProviderOperation> {
        ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
            val selection = "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf(contact.id.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            withSelection(selection, selectionArgs)
            operations.add(build())
        }

        return operations
    }

    fun addContactsToGroup(contacts: ArrayList<Contact>, groupId: Long) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.forEach {
                ContentProviderOperation.newInsert(Data.CONTENT_URI).apply {
                    withValue(Data.RAW_CONTACT_ID, it.id)
                    withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    fun removeContactsFromGroup(contacts: ArrayList<Contact>, groupId: Long) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.forEach {
                ContentProviderOperation.newDelete(Data.CONTENT_URI).apply {
                    val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Data.DATA1} = ?"
                    val selectionArgs = arrayOf(it.contactId.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString())
                    withSelection(selection, selectionArgs)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }

    private fun addFullSizePhoto(contactId: Long, fullSizePhotoData: ByteArray) {
        val baseUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, contactId)
        val displayPhotoUri = Uri.withAppendedPath(baseUri, RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        val fileDescriptor = context.contentResolver.openAssetFileDescriptor(displayPhotoUri, "rw")
        val photoStream = fileDescriptor!!.createOutputStream()
        photoStream.write(fullSizePhotoData)
        photoStream.close()
        fileDescriptor.close()
    }

    fun getContactMimeTypeId(contactId: String, mimeType: String): String {
        val uri = Data.CONTENT_URI
        val projection = arrayOf(Data._ID, Data.RAW_CONTACT_ID, Data.MIMETYPE)
        val selection = "${Data.MIMETYPE} = ? AND ${Data.RAW_CONTACT_ID} = ?"
        val selectionArgs = arrayOf(mimeType, contactId)


        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Data._ID)
            }
        }
        return ""
    }

    private fun toggleFavorites(contacts: ArrayList<Contact>, addToFavorites: Boolean) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            contacts.filter { !it.isPrivate() }.map { it.contactId.toString() }.forEach {
                val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, it)
                ContentProviderOperation.newUpdate(uri).apply {
                    withValue(Contacts.STARRED, if (addToFavorites) 1 else 0)
                    operations.add(build())
                }

                if (operations.size % BATCH_SIZE == 0) {
                    context.contentResolver.applyBatch(AUTHORITY, operations)
                    operations.clear()
                }
            }
            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }


    fun updateRingtone(contactId: String, newUri: String) {
        try {
            val operations = ArrayList<ContentProviderOperation>()
            val uri = Uri.withAppendedPath(Contacts.CONTENT_URI, contactId)
            ContentProviderOperation.newUpdate(uri).apply {
                withValue(Contacts.CUSTOM_RINGTONE, newUri)
                operations.add(build())
            }

            context.contentResolver.applyBatch(AUTHORITY, operations)
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }


    fun getDuplicatesOfContact(contact: Contact, addOriginal: Boolean, callback: (ArrayList<Contact>) -> Unit) {
        ensureBackgroundThread {
            getContacts(true, true) { contacts ->
                val duplicates =
                    contacts.filter { it.id != contact.id && it.getHashToCompare() == contact.getHashToCompare() }.toMutableList() as ArrayList<Contact>
                if (addOriginal) {
                    duplicates.add(contact)
                }
                callback(duplicates)
            }
        }
    }

    fun getContactsToExport(selectedContactSources: Set<String>, callback: (List<Contact>) -> Unit) {
        getContacts(getAll = true) { receivedContacts ->
            val contacts = receivedContacts.filter { it.source in selectedContactSources }
            callback(contacts)
        }
    }

}
