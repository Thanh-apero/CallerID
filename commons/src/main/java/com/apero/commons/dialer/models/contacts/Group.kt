package com.apero.commons.dialer.models.contacts

import java.io.Serializable

@kotlinx.serialization.Serializable
data class Group(
    val id: Long?,
    val title: String,
    val contactsCount: Int = 0
) : Serializable