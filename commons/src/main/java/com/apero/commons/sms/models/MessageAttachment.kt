package com.apero.commons.sms.models


data class MessageAttachment(
    val id: Long,
    var text: String,
    var attachments: ArrayList<Attachment>
)
