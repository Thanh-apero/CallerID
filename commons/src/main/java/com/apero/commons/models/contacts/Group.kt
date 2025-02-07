package com.apero.commons.models.contacts

import com.simplemobiletools.commons.helpers.FIRST_GROUP_ID
import java.io.Serializable

@kotlinx.serialization.Serializable
data class Group(
    val id: Long?,
    val title: String,
    val contactsCount: Int = 0
) : Serializable