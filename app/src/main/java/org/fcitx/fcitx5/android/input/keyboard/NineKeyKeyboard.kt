/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction

/**
 * 9-key (T9 / 九宫格) keyboard layout.
 *
 * UX flow for alphabet keys:
 * - Tap once: shows popup menu with all letters (✓ on first letter)
 * - 500ms: auto-commits the first letter (unless user selects different one)
 * - Tap same key again within 500ms: cycles ✓ to next letter
 * - Tap different key: commits current selection, starts new popup
 * - In popup: tap a letter → commits that letter immediately
 *
 * The key label always shows the full combo (ABC, DEF, etc.) — never changes.
 */
@SuppressLint("ViewConstructor")
class NineKeyKeyboard(
    context: Context,
    theme: Theme
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

    override fun onAttach() {
        // IMPORTANT: override alphabet key click listeners BEFORE any interaction.
        // BaseKeyboard's init{} set Behavior.Press listeners that commit immediately.
        // We replace them with popup-aware handlers.
        bindAlphabetKeys()
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

    // ── Bind alphabet keys to popup-aware click handlers ───────────────────
    //
    // BaseKeyboard.createKeyView() sets setOnClickListener for Behavior.Press.
    // Since onAttach() runs after init{}, we can call setOnClickListener again
    // to OVERRIDE the default listener and replace it with our popup logic.

    private fun bindAlphabetKeys() {
        val alphabetDefs = Layout.flatten().filterIsInstance<NineKeyAlphabetKey>()
        for (def in alphabetDefs) {
            val view = findViewById<KeyView>(def.viewIdRes)
            if (view != null) {
                // Override the Behavior.Press click listener
                view.setOnClickListener { _ ->
                    handleAlphabetClick(def, view)
                }
            }
        }
    }

    /**
     * Handle tap on an alphabet key (ABC, DEF, etc.):
     * - Same key tapped again → cycle to next letter
     * - Different key → start new pending
     * - Always shows popup with ✓ on selected letter
     * - Resets 500ms auto-commit timer
     */
    private fun handleAlphabetClick(def: NineKeyAlphabetKey, view: View) {
        val viewId = view.id

        if (activeViewId == viewId) {
            // Same key → cycle letter
            selectedLetterIndex = (selectedLetterIndex + 1) % def.letters.length
        } else {
            // New key → reset state
            activeKeyDef = def
            activeViewId = viewId
            selectedLetterIndex = 0
        }

        showLetterPopup(def, view, selectedLetterIndex)

        // Reset the 500ms auto-commit timer
        handler.removeCallbacks(autoCommitRunnable)
        handler.postDelayed(autoCommitRunnable, AUTO_COMMIT_DELAY)
    }

    /**
     * Show popup menu with all letters of this key, ✓ on [selectedIdx].
     */
    private fun showLetterPopup(def: NineKeyAlphabetKey, view: View, selectedIdx: Int) {
        val letters = def.letters
        val items = letters.mapIndexed { idx, ch ->
            val marker = if (idx == selectedIdx) " ✓" else "  "
            KeyDef.Popup.Menu.Item(
                label = ch.toString() + marker,
                icon = 0,
                action = KeyAction.CommitAction(ch.toString())
            )
        }.toTypedArray()

        onPopupAction(
            PopupAction.ShowMenuAction(
                view.id,
                KeyDef.Popup.Menu(items),
                (view as KeyView).bounds
            )
        )
    }

    /**
     * Commit the currently selected letter via the key action listener.
     * Called either by:
     *   (a) user tapping a letter in the popup → onPopupAction handles
     *   (b) 500ms auto-commit timer firing
     */
    private fun commitCurrentLetter() {
        val def = activeKeyDef ?: return
        val letter = def.letters.getOrNull(selectedLetterIndex) ?: return
        // Route through super.onAction so it reaches keyActionListener
        super.onAction(KeyAction.CommitAction(letter.toString()), KeyActionListener.Source.Keyboard)
        resetState()
    }

    // ── Popup action handling ──────────────────────────────────────────────
    //
    // When user taps a letter in the popup menu, PopupComponent fires TriggerAction.
    // We intercept it, cancel auto-commit, get the selected letter from the popup,
    // and commit it.

    override fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.TriggerAction -> {
                // Cancel pending 500ms auto-commit
                handler.removeCallbacks(autoCommitRunnable)

                // Let PopupComponent resolve the focused item → outAction gets set
                super.onPopupAction(action)
                val selectedAction = action.outAction

                // Commit the selected letter
                if (selectedAction is KeyAction.CommitAction) {
                    super.onAction(selectedAction, KeyActionListener.Source.Popup)
                }

                resetState()
            }
            is PopupAction.DismissAction -> {
                // User dismissed popup without selecting → keep pending,
                // auto-commit will fire in ~500ms from first tap
                super.onPopupAction(action)
            }
            else -> super.onPopupAction(action)
        }
    }
}
