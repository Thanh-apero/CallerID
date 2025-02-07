package com.apero.commons.helpers

import com.apero.commons.models.BlockedNumber
import com.simplemobiletools.commons.helpers.BLOCKED_NUMBERS_EXPORT_DELIMITER
import com.simplemobiletools.commons.helpers.ExportResult
import java.io.OutputStream

object BlockedNumbersExporter {

    fun exportBlockedNumbers(
        blockedNumbers: ArrayList<BlockedNumber>,
        outputStream: OutputStream?,
        callback: (result: ExportResult) -> Unit,
    ) {
        if (outputStream == null) {
            callback.invoke(ExportResult.EXPORT_FAIL)
            return
        }

        try {
            outputStream.bufferedWriter().use { out ->
                out.write(blockedNumbers.joinToString(BLOCKED_NUMBERS_EXPORT_DELIMITER) {
                    it.number
                })
            }
            callback.invoke(ExportResult.EXPORT_OK)
        } catch (e: Exception) {
            callback.invoke(ExportResult.EXPORT_FAIL)
        }
    }
}
