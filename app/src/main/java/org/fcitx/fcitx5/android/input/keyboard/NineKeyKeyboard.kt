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
                NineKeyAlphabetKey("A", "2", "ABC", R.id.button_ninekey_alpha_2),
                NineKeyAlphabetKey("D", "3", "DEF", R.id.button_ninekey_alpha_3)
            ),
            // Row 2
            listOf(
                NineKeyAlphabetKey("G", "4", "GHI", R.id.button_ninekey_alpha_4),
                NineKeyAlphabetKey("J", "5", "JKL", R.id.button_ninekey_alpha_5),
                NineKeyAlphabetKey("M", "6", "MNO", R.id.button_ninekey_alpha_6)
            ),
            // Row 3
            listOf(
                NineKeyAlphabetKey("P", "7", "PQRS", R.id.button_ninekey_alpha_7),
                NineKeyAlphabetKey("T", "8", "TUV", R.id.button_ninekey_alpha_8),
                NineKeyAlphabetKey("W", "9", "WXYZ", R.id.button_ninekey_alpha_9)
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
        var currentLetter: String = keyDef.primaryLetter
    )

    private val handler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitAndReset() }

    // ── Key view bindings ──────────────────────────────────────────────────

    val space: TextKeyView by lazy { findViewById(R.id.button_ninekey_space) }

    // All alphabet key views that need multi-press wiring
    private lateinit var alphaKeys: List<Pair<NineKeyAlphabetKey, KeyView>>

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

    // ── Multi-press wiring (called after key views are created) ─────────────

    override fun postInit() {
        super.postInit()
        // Collect all NineKeyAlphabetKey views by their unique view IDs
        alphaKeys = Layout.flatten()
            .filterIsInstance<NineKeyAlphabetKey>()
            .map { keyDef -> keyDef to findViewById<KeyView>(keyDef.viewIdRes) }

        // Replace each alphabet key's click handler with multi-press logic
        alphaKeys.forEach { (keyDef, view) ->
            view.setOnClickListener {
                handleMultiPress(keyDef, view)
            }
        }
    }

    // ── Multi-press logic ──────────────────────────────────────────────────

    private fun handleMultiPress(keyDef: NineKeyAlphabetKey, view: View) {
        val viewId = view.id

        // Cancel any pending commit
        handler.removeCallbacks(commitRunnable)

        // Get or create state for this key
        val state = multiPressState.getOrPut(viewId) {
            MultiPressState(keyDef)
        }

        // Increment cycle position
        state.pressCount++
        val letters = keyDef.letters
        val idx = (state.pressCount - 1) % letters.length
        state.currentLetter = letters.substring(idx, idx + 1)

        // Update key label to show the selected letter
        updateKeyLabel(view, state.currentLetter)

        // Show popup with all letter options and a ✓ on the selected one
        showLetterPopup(view, viewId, letters, idx)

        // Schedule auto-commit if no more presses arrive
        handler.postDelayed(commitRunnable, MULTI_PRESS_DELAY)
    }

    private fun updateKeyLabel(view: View, text: String) {
        when (view) {
            is TextKeyView -> view.mainText.text = text
            is AltTextKeyView -> view.mainText.text = text
            is ImageTextKeyView -> view.mainText.text = text
        }
    }

    private fun showLetterPopup(view: View, viewId: Int, letters: String, selectedIdx: Int) {
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
                (view as KeyView).bounds
            )
        )
    }

    private fun commitAndReset() {
        // The last selected letter has already been "shown" on the key.
        // When the user moves to another key, that letter becomes the committed one.
        // We just reset state here.
        multiPressState.clear()
    }

    // ── Action handling ────────────────────────────────────────────────────

    override fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source
    ) {
        when (action) {
            is KeyAction.CommitAction -> {
                // Reset multi-press on any character commit
                multiPressState.clear()
                handler.removeCallbacks(commitRunnable)
            }
            is KeyAction.LayoutSwitchAction -> {
                multiPressState.clear()
                handler.removeCallbacks(commitRunnable)
            }
            is KeyAction.LangSwitchAction -> {
                multiPressState.clear()
                handler.removeCallbacks(commitRunnable)
            }
            else -> {}
        }
        super.onAction(action, source)
    }

    // ── Popup handling ─────────────────────────────────────────────────────
    //
    // When the user taps the popup menu to select a letter, TriggerAction fires.
    // We intercept it here to first reset multi-press state.

    override fun onPopupAction(action: PopupAction) {
        when (action) {
            is PopupAction.TriggerAction -> {
                // Reset multi-press state on popup selection
                multiPressState.clear()
                handler.removeCallbacks(commitRunnable)
                super.onPopupAction(action)
            }
            else -> super.onPopupAction(action)
        }
    }
}
