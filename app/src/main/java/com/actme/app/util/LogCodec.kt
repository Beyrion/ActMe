package com.actme.app.util

import android.util.Base64

object LogCodec {
    fun utf8Base64(text: String?): String {
        val normalized = text.orEmpty()
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
