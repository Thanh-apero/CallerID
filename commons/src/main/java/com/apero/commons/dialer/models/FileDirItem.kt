package com.apero.commons.dialer.models

import com.apero.commons.dialer.extensions.normalizeString
import com.apero.commons.dialer.helpers.AlphanumericComparator
import com.apero.commons.dialer.helpers.SORT_BY_DATE_MODIFIED
import com.apero.commons.dialer.helpers.SORT_BY_NAME
import com.apero.commons.dialer.helpers.SORT_BY_SIZE
import com.apero.commons.dialer.helpers.SORT_DESCENDING
import com.apero.commons.dialer.helpers.SORT_USE_NUMERIC_VALUE
import java.io.File

open class FileDirItem(
    val path: String,
    val name: String = "",
    var isDirectory: Boolean = false,
    var children: Int = 0,
    var size: Long = 0L,
    var modified: Long = 0L,
    var mediaStoreId: Long = 0L
) :
    Comparable<FileDirItem> {
    companion object {
        var sorting = 0
    }

    override fun toString() =
        "FileDirItem(path=$path, name=$name, isDirectory=$isDirectory, children=$children, size=$size, modified=$modified, mediaStoreId=$mediaStoreId)"

    override fun compareTo(other: FileDirItem): Int {
        return if (isDirectory && !other.isDirectory) {
            -1
        } else if (!isDirectory && other.isDirectory) {
            1
        } else {
            var result: Int
            when {
                sorting and SORT_BY_NAME != 0 -> {
                    result = if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                        AlphanumericComparator()
                            .compare(
                                name.normalizeString().lowercase(),
                                other.name.normalizeString().lowercase()
                            )
                    } else {
                        name.normalizeString().lowercase()
                            .compareTo(other.name.normalizeString().lowercase())
                    }
                }

                sorting and SORT_BY_SIZE != 0 -> result = when {
                    size == other.size -> 0
                    size > other.size -> 1
                    else -> -1
                }

                sorting and SORT_BY_DATE_MODIFIED != 0 -> {
                    result = when {
                        modified == other.modified -> 0
                        modified > other.modified -> 1
                        else -> -1
                    }
                }

                else -> {
                    result = getExtension().lowercase().compareTo(other.getExtension().lowercase())
                }
            }

            if (sorting and SORT_DESCENDING != 0) {
                result *= -1
            }
            result
        }
    }

    fun getExtension() = if (isDirectory) name else path.substringAfterLast('.', "")


    fun getSignature(): String {
        val lastModified = if (modified > 1) {
            modified
        } else {
            File(path).lastModified()
        }

        return "$path-$lastModified-$size"
    }

}

fun FileDirItem.asReadOnly() =
    FileDirItemReadOnly(
        path = path,
        name = name,
        isDirectory = isDirectory,
        children = children,
        size = size,
        modified = modified,
        mediaStoreId = mediaStoreId
    )

fun FileDirItemReadOnly.asFileDirItem() =
    FileDirItem(
        path = path,
        name = name,
        isDirectory = isDirectory,
        children = children,
        size = size,
        modified = modified,
        mediaStoreId = mediaStoreId
    )

class FileDirItemReadOnly(
    path: String,
    name: String = "",
    isDirectory: Boolean = false,
    children: Int = 0,
    size: Long = 0L,
    modified: Long = 0L,
    mediaStoreId: Long = 0L
) : FileDirItem(path, name, isDirectory, children, size, modified, mediaStoreId)
