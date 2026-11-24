package com.fct.tc4.ui.misc

/** Only ordinary launcher/Recents visits return automatically to a live desktop. */
internal fun shouldReturnToX11(
    sessionActive: Boolean,
    managementRequested: Boolean,
    hasPendingAction: Boolean
): Boolean = sessionActive && !managementRequested && !hasPendingAction
