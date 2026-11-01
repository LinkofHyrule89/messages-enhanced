package com.ubermicrostudios.messagesenhanced.update

/**
 * After an update, show that version's notes once.
 * A missing stored code is a fresh install unless the package was updated in place: record the current code and stay quiet.
 */
object WhatsNewPolicy {
    fun shouldShow(storedVersionCode: Int?, currentVersionCode: Int, updatedInPlace: Boolean = false): Boolean {
        if (currentVersionCode <= 0) return false
        // No stored code: an install from before this feature counts as an update when the package was updated in place.
        if (storedVersionCode == null) return updatedInPlace
        return currentVersionCode > storedVersionCode
    }
}
