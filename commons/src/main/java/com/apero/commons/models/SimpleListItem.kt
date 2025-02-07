package com.apero.commons.models

import android.os.Parcelable

data class SimpleListItem(val id: Int, val textRes: Int, val imageRes: Int? = null, val selected: Boolean = false)  {

    companion object {
        fun areItemsTheSame(old: SimpleListItem, new: SimpleListItem): Boolean {
            return old.id == new.id
        }

        fun areContentsTheSame(old: SimpleListItem, new: SimpleListItem): Boolean {
            return old.imageRes == new.imageRes && old.textRes == new.textRes && old.selected == new.selected
        }
    }
}
