package com.qmusic.app

import android.net.Uri

/**
 * What ringtones need from the screen in front: the system's contact picker, the Contacts permission and the page
 * where the user allows changing the phone's sounds. [MainActivity] fills these in while it is open; each call
 * waits for the user to come back.
 */
object RingtoneBridge {
    /** Opens the contact picker; the picked contact's address, or null when the user backed out. */
    @Volatile
    var pickContact: (suspend () -> Uri?)? = null

    /** Asks for the Contacts permission (if it is not given yet); whether it is given. */
    @Volatile
    var requestContactsPermission: (suspend () -> Boolean)? = null

    /** Opens the "change system settings" page for the app and returns once the user has come back from it. */
    @Volatile
    var openWriteSettings: (suspend () -> Unit)? = null
}
