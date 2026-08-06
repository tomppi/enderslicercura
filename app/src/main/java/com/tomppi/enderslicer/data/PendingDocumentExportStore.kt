package com.tomppi.enderslicer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/** Records a user-visible SAF destination until the complete payload is durably closed. */
class PendingDocumentExportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun begin(uri: Uri) {
        check(preferences.edit().putString(KEY_PENDING_URI, uri.toString()).commit()) {
            "Unable to record the pending export destination"
        }
    }

    fun complete(uri: Uri) {
        if (preferences.getString(KEY_PENDING_URI, null) == uri.toString()) {
            check(preferences.edit().remove(KEY_PENDING_URI).commit()) {
                "Unable to complete the export transaction"
            }
        }
    }

    fun fail(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
        complete(uri)
    }

    fun recover(resolver: ContentResolver) {
        val raw = preferences.getString(KEY_PENDING_URI, null) ?: return
        runCatching { resolver.delete(Uri.parse(raw), null, null) }
        preferences.edit().remove(KEY_PENDING_URI).commit()
    }

    private companion object {
        const val PREFERENCES = "enderslicer-pending-exports-v1"
        const val KEY_PENDING_URI = "pending-uri"
    }
}
