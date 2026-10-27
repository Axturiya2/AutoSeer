package com.autoseer.scripts

import com.autoseer.libautomata.IPattern

/**
 * One factor stage the sweep should play. [name] is the library file name (display).
 * [id] is the game's 精靈編號 — the hidden tag embedded in the card PNG ([PetIdTag]),
 * or for legacy cards the trailing `_數字` of the file name; when present it
 * drives the fast search-based navigation (type the ID into the in-game search box →
 * the single result → tap it), which replaces scroll-and-match entirely. [pattern]
 * (the captured card crop) is no longer used for navigation and may be null.
 */
data class FactorTarget(val name: String, val pattern: IPattern? = null, val id: String? = null)
