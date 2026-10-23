package org.openandroidauto.protocol

/** Android key values are stable; keeping this policy pure makes wheel dispatch testable. */
object CarKeyActions {
    enum class Action { NONE, ASSISTANT, MEDIA, CALLS, CANCEL_PENDING }
    fun forKey(androidCode: Int): Action = when (androidCode) {
        84, 219, 231 -> Action.ASSISTANT // SEARCH, ASSIST, VOICE_ASSIST
        5 -> Action.CALLS // Confirm an explicitly displayed number, otherwise show contacts.
        6 -> Action.CANCEL_PENDING // Cancel only our pending flow; never invoke phone hangup.
        79, 85, 86, 87, 88, 126, 127 -> Action.MEDIA
        else -> Action.NONE
    }
}
