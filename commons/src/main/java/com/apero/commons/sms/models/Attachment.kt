package com.apero.commons.sms.models

import android.net.Uri

data class Attachment(
    var id: Long?,
    var messageId: Long,
    var uriString: String,
    var mimetype: String,
    var width: Int,
    var height: Int,
    var filename: String) {

    fun getUri() = Uri.parse(uriString)
}
