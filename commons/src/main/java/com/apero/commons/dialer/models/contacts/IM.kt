package com.apero.commons.dialer.models.contacts

import kotlinx.serialization.Serializable

@Serializable
data class IM(
    var value: String,
    var type: Int,
    var label: String
)
