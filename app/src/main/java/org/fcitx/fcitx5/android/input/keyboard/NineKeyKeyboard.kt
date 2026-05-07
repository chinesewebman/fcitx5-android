/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * 9-key (T9 / 九宫格) keyboard layout.
 *
 * UX flow for alphabet keys:
 * - Tap once: show popup with all letters (first selected), start 500ms auto-commit timer
 * - Tap same key again within 500ms: cycle to next letter, reset timer
 * - Auto-commit fires: commit selected letter via keyActionListener (goes through CommonKeyActionListener)
 * - Tap popup letter: commit via keyActionListener (CommonKeyActionListener), reset state
 *
 * The key label always shows the full combo (ABC, DEF, etc.) — never changes.
 *
 * Implementation:
 * - NineKeyAlphabetKey uses Behavior.Press(CommitAction(letters.first()))
 * - onAction(CommitAction): find NineKeyAlphabetKey from the action's text (first letter lookup),
 *   set active key, show popup, start timer. Return without calling super to prevent immediate commit.
 * - Popup menu items: CommitAction(ch.toString()) — on tap, goes to CommonKeyActionListener → commits
 * - auto-commit (timer): calls keyActionListener.onKeyAction(CommitAction(letter)) → CommonKeyActionListener
 */
@SuppressLint("ViewConstructor")
class NineKeyKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "NineKey"

        /** Time window (ms) before auto-committing the pending letter */
        private const val AUTO_COMMIT_DELAY = 500L

        /**
         * 9-key layout:
         * Row 1:  1 ·,   2 ABC,  3 DEF
         * Row 2:  4 GHI, 5 JKL,  6 MNO
         * Row 3:  7 PQRS,8 TUV,  9 WXYZ
         * Row 4:  *符,   0空格,  #ABC
         */
        val Layout: List<List<KeyDef>> = listOf(
            // Row 1
            listOf(
                NineKeyPunctKey(".", ","),
                NineKeyAlphabetKey("ABC", "2", R.id.button_ninekey_alpha_2),
                NineKeyAlphabetKey("DEF", "3", R.id.button_ninekey_alpha_3)
            ),
            // Row 2
            listOf(
                NineKeyAlphabetKey("GHI", "4", R.id.button_ninekey_alpha_4),
                NineKeyAlphabetKey("JKL", "5", R.id.button_ninekey_alpha_5),
                NineKeyAlphabetKey("MNO", "6", R.id.button_ninekey_alpha_6)
            ),
            // Row 3
            listOf(
                NineKeyAlphabetKey("PQRS", "7", R.id.button_ninekey_alpha_7),
                NineKeyAlphabetKey("TUV", "8", R.id.button_ninekey_alpha_8),
                NineKeyAlphabetKey("WXYZ", "9", R.id.button_ninekey_alpha_9)
            ),
            // Row 4
            listOf(
                NineKeySymbolKey("符", PickerWindow.Key.Symbol.name, 0.2f),
                NineKeySpaceKey(),
                NineKeyLayoutSwitchKey("ABC", TextKeyboard.Name, 0.2f)
            )
        )
    }

    // ── Multi-press state ──────────────────────────────────────────────────

    /** The currently active (pending) NineKeyAlphabetKey, or null if none */
    private var activeKeyDef: NineKeyAlphabetKey? = null
    /** The view ID of the currently active key */
    private var activeViewId: Int = -1
    /** Index of the currently selected letter (for cycling) */
    private var selectedLetterIndex: Int = 0

    /** Lookup: first letter → NineKeyAlphabetKey (built in postInit) */
    private lateinit var firstLetterToDef: Map<Char, NineKeyAlphabetKey>

    private val handler = Handler(Looper.getMainLooper())
    private val autoCommitRunnable = Runnable {
        commitCurrentLetter()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // NineKey has no language key
    }

    init {
        AppPrefs.getInstance().keyboard.showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    override fun postInit() {
        // Build the first-letter → def lookup map
        firstLetterToDef = Layout.flatten()
            .filterIsInstance<NineKeyAlphabetKey>()
            .associateBy { it.letters.first() }
        Log.w("NineKeyKB", "postInit: firstLetterToDef=$firstLetterToDef")
    }

    override fun onDetach() {
        resetState()
    }

    private fun resetState() {
        activeKeyDef = null
        activeViewId = -1
        selectedLetterIndex = 0
        handler.removeCallbacks(autoCommitRunnable)
    }

    // ── Finding the KeyView for a given NineKeyAlphabetKey ───────────────

    /** Find the KeyView for a given viewId among our children */
    private fun findKeyView(viewId: Int): KeyView? {
        return findViewById(viewId)
    }

    // ── onAction interception ───────────────────────────────────────────────
    //
    // NineKeyAlphabetKey uses Behavior.Press(CommitAction(letter)) where letter = letters.first().
    // When user taps, BaseKeyboard's setOnClickListener fires: onAction(CommitAction(letter))
    //
    // We intercept here. Since we can't know WHICH NineKeyAlphabetKey was pressed from the
    // CommitAction alone, we use a first-letter lookup built in postInit().
    //
    // Flow:
    // 1. Look up NineKeyAlphabetKey by the CommitAction's text (its first letter)
    // 2. If same key already active → cycle letter, update popup, reset timer
    // 3. If different key active → commit current (if any), set new active, show popup, start timer
    // 4. If no active key → set active, show popup, start timer
    // 5. Return WITHOUT calling super.onAction — we handle commit ourselves (via timer or popup tap)

    override fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source
    ) {
        if (action is KeyAction.CommitAction) {
            Log.w("NineKeyKB", "onAction CommitAction '${action.text}' source=$source")
            val letter = action.text.singleOrNull() ?: run {
                Log.w("NineKeyKB", "  → singleOrNull failed, super.onAction")
                super.onAction(action, source)
                return
            }
            Log.w("NineKeyKB", "  letter='$letter' activeViewId=$activeViewId")

            // Find the NineKeyAlphabetKey for this letter
            val def = firstLetterToDef[letter]
            Log.w("NineKeyKB", "  def=$def")

            if (def == null) {
                // Not a nine-key alphabet letter — normal commit
                Log.w("NineKeyKB", "  → def null, super.onAction")
                super.onAction(action, source)
                return
            }

            val view = findKeyView(def.viewIdRes)
            Log.w("NineKeyKB", "  view=$view viewId=${def.viewIdRes}")

            if (view == null) {
                Log.w("NineKeyKB", "  → view null, super.onAction")
                super.onAction(action, source)
                return
            }

            when {
                // Same key tapped again → cycle letter
                activeViewId == def.viewIdRes -> {
                    selectedLetterIndex = (selectedLetterIndex + 1) % def.letters.length
                    showLetterPopup(def, view, selectedLetterIndex)
                    handler.removeCallbacks(autoCommitRunnable)
                    handler.postDelayed(autoCommitRunnable, AUTO_COMMIT_DELAY)
                    Log.w("NineKeyKB", "  → HOLD (cycle), idx=$selectedLetterIndex")
                    // HOLD — don't call super
                }
                // Different key (or no active key) → start new pending
                else -> {
                    // If there was a previous active key, commit it first
                    if (activeKeyDef != null) {
                        commitCurrentLetter()
                    }
                    activeKeyDef = def
                    activeViewId = def.viewIdRes
                    selectedLetterIndex = 0
                    showLetterPopup(def, view, selectedLetterIndex)
                    handler.removeCallbacks(autoCommitRunnable)
                    handler.postDelayed(autoCommitRunnable, AUTO_COMMIT_DELAY)
                    Log.w("NineKeyKB", "  → HOLD (new key), popup shown")
                    // HOLD — don't call super
                }
            }
        } else {
            // All other actions fall through normally
            super.onAction(action, source)
        }
    }

    /**
     * Show popup menu with all letters of this key, ✓ on [selectedIdx].
     */
    private fun showLetterPopup(def: NineKeyAlphabetKey, view: KeyView, selectedIdx: Int) {
        val letters = def.letters
        val items = letters.mapIndexed { idx, ch ->
            val marker = if (idx == selectedIdx) " ✓" else ""
            KeyDef.Popup.Menu.Item(
                label = "$ch$marker",
                icon = 0,
                action = KeyAction.CommitAction(ch.toString())
            )
        }.toTypedArray()

        onPopupAction(
            PopupAction.ShowMenuAction(
                view.id,
                KeyDef.Popup.Menu(items),
                view.bounds
            )
        )
    }

    /**
     * Commit the currently selected letter via keyActionListener.
     * Goes through CommonKeyActionListener (async, won't close keyboard immediately).
     */
    private fun commitCurrentLetter() {
        val def = activeKeyDef ?: return
        val letter = def.letters.getOrNull(selectedLetterIndex) ?: return
        resetState()
        keyActionListener?.onKeyAction(
            KeyAction.CommitAction(letter.toString()),
            KeyActionListener.Source.Keyboard
        )
    }

    // ── Popup action handling ──────────────────────────────────────────────
    //
    // When user taps a letter in the popup menu:
    // 1. PopupMenuUi.onTrigger() fires → CommitAction(ch)
    // 2. PopupComponent.triggerFocused() → returns CommitAction
    // 3. CommonKeyActionListener.onKeyAction(CommitAction) → commitAndReset() + commitText()
    // 4. CommonKeyActionListener also calls onPopupAction(TriggerAction) → NineKeyKeyboard.onPopupAction
    //
    // We intercept TriggerAction here to reset our state when popup is used.

    override fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.TriggerAction -> {
                // Cancel pending auto-commit
                handler.removeCallbacks(autoCommitRunnable)
                // Let super (PopupComponent) handle TriggerAction → it returns CommitAction
                // Then CommonKeyActionListener will commit it
                super.onPopupAction(action)
                // Reset our state — CommonKeyActionListener handles the actual commit
                resetState()
            }
            is PopupAction.ShowMenuAction -> {
                // Popup about to show — nothing extra needed, state already set in onAction
                super.onPopupAction(action)
            }
            is PopupAction.DismissAction -> {
                // Popup dismissed by tap outside — keep pending, auto-commit will fire
                super.onPopupAction(action)
            }
            else -> super.onPopupAction(action)
        }
    }
}
