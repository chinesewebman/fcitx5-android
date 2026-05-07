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
import org.fcitx.fcitx5.android.input.popup.PopupActionListener

/**
 * 9-key (T9 / 九宫格) keyboard layout.
 *
 * Multi-press letter cycling:
 * - Press once → first letter (e.g. A)
 * - Press again within 500ms → cycle to next letter (B, then C...)
 * - After 500ms timeout → commit the currently selected letter and reset
 *
 * The popup shows all available letters with a ✓ on the currently selected one.
 */
@SuppressLint("ViewConstructor")
class NineKeyKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "NineKey"

        /** Time window (ms) for cycling through letters on the same key */
        private const val MULTI_PRESS_DELAY = 500L

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

    /** Maps viewId → current multi-press state for that key */
    private val multiPressState = mutableMapOf<Int, MultiPressState>()

    private data class MultiPressState(
        val keyDef: NineKeyAlphabetKey,
        var pressCount: Int = 0,
        var currentLetter: String = keyDef.letters.first().toString()
    )

    private val handler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitCurrentLetter() }

    // ── Key view bindings ──────────────────────────────────────────────────

    val space: TextKeyView by lazy { findViewById(R.id.button_ninekey_space) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // NineKey doesn't have a lang key in the default layout
    }

    init {
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onAttach() {
        multiPressState.clear()
        handler.removeCallbacks(commitRunnable)
    }

    override fun onDetach() {
        multiPressState.clear()
        handler.removeCallbacks(commitRunnable)
    }

    // ── Multi-press via onAction interception ───────────────────────────────
    //
    // KeyView's built-in click fires a CommitAction(primaryLetter) → onAction().
    // We intercept it here to manage multi-press cycling, then commit via
    // keyActionListener when the 500ms timeout fires.

    override fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source
    ) {
        when (action) {
            is KeyAction.CommitAction -> {
                // Find which NineKey alphabet key this commit came from
                val viewId = findAlphabetKeyViewId(action.text)
                if (viewId != -1) {
                    // Cancel any pending commit
                    handler.removeCallbacks(commitRunnable)

                    // Get or create state for this key
                    val keyDef = Layout.flatten()
                        .filterIsInstance<NineKeyAlphabetKey>()
                        .first { findViewById<KeyView>(it.viewIdRes).id == viewId }

                    val state = multiPressState.getOrPut(viewId) {
                        MultiPressState(keyDef)
                    }

                    // Cycle to next letter
                    state.pressCount++
                    val idx = (state.pressCount - 1) % keyDef.letters.length
                    state.currentLetter = keyDef.letters.substring(idx, idx + 1)

                    // Show popup with all letters, ✓ on selected
                    val view = findViewById<KeyView>(viewId)
                    showLetterPopup(view, viewId, keyDef.letters, idx)

                    // Schedule auto-commit if no more presses arrive
                    handler.postDelayed(commitRunnable, MULTI_PRESS_DELAY)
                    return  // Don't call super — we're holding the commit
                }
            }
            else -> {}
        }
        super.onAction(action, source)
    }

    private fun findAlphabetKeyViewId(text: String): Int {
        return Layout.flatten()
            .filterIsInstance<NineKeyAlphabetKey>()
            .firstOrNull { keyDef ->
                keyDef.letters.contains(text)
            }?.let { keyDef ->
                findViewById<KeyView>(keyDef.viewIdRes)?.id ?: -1
            } ?: -1
    }

    private fun commitCurrentLetter() {
        val state = multiPressState.values.firstOrNull() ?: return
        // Commit the selected letter through the normal action flow
        keyActionListener?.onKeyAction(
            KeyAction.CommitAction(state.currentLetter),
            KeyActionListener.Source.Keyboard
        )
        multiPressState.clear()
    }

    // ── Popup ─────────────────────────────────────────────────────────────

    private fun showLetterPopup(view: KeyView, viewId: Int, letters: String, selectedIdx: Int) {
        val items = letters.mapIndexed { idx, ch ->
            val marker = if (idx == selectedIdx) " ✓" else ""
            KeyDef.Popup.Menu.Item(
                "$ch$marker",
                0,
                KeyAction.CommitAction(ch.toString())
            )
        }.toTypedArray()

        onPopupAction(
            PopupAction.ShowMenuAction(
                viewId,
                KeyDef.Popup.Menu(items),
                view.bounds
            )
        )
    }

    // ── Popup handling ─────────────────────────────────────────────────────
    //
    // When the user taps the popup menu to select a letter, TriggerAction fires.
    // We intercept it to commit that specific letter and reset state.

    override fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.TriggerAction -> {
                // Find the selected letter from popup and commit it
                val triggerAction = action as? PopupAction.TriggerAction
                // The TriggerAction carries the viewId; look up the popup menu
                // For now, commit whatever is selected in multi-press state
                multiPressState.values.firstOrNull()?.let { state ->
                    keyActionListener?.onKeyAction(
                        KeyAction.CommitAction(state.currentLetter),
                        KeyActionListener.Source.Popup
                    )
                }
                multiPressState.clear()
                handler.removeCallbacks(commitRunnable)
                super.onPopupAction(action)
            }
            else -> super.onPopupAction(action)
        }
    }
}
