package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

data class ResolvedCaller(
    val number: String,
    val displayName: String?,
    val trusted: TrustedContactConfig?,
)

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"
    }

    fun resolve(number: String): ResolvedCaller {
        val trusted = PocketDaemonApp.instance?.findTrustedContact(number)
        if (trusted != null) {
            Log.i(TAG, "Trusted contact match: ${trusted.name} (${trusted.number})")
            return ResolvedCaller(
                number = number,
                displayName = trusted.name.ifBlank { lookupContact(number) },
                trusted = trusted,
            )
        }

        val contactName = lookupContact(number)
        Log.i(TAG, "Resolved $number → ${contactName ?: "unknown"} (not trusted)")
        return ResolvedCaller(number = number, displayName = contactName, trusted = null)
    }

    private fun lookupContact(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $number: ${e.message}")
            null
        }
    }
}
