package com.simplemobiletools.commons.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes


data class LanguageContributor(@DrawableRes val iconId: Int, @StringRes val labelId: Int, @StringRes val contributorsId: Int)
