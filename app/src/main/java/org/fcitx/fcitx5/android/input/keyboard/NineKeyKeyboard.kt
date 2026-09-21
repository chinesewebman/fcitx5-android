/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction

/**
 * 9-key (T9 / 九宫格) keyboard layout.
 *
 * Letter selection is multi-tap: pressing an alphabet key advances the pending letter
 * through that key's combo (ABC → A, B, C, A …); pressing a different key commits the
 * pending one first. [AUTO_COMMIT_DELAY] ms after the last press the pending letter is
 * sent to fcitx5, which builds preedit and candidates exactly like the full keyboard —
 * so pinyin (and any other fcitx5 engine) works. The pending letter is shown as a
 * preview bubble above the key.
 *
 * Letters are committed as [KeyAction.FcitxKeyAction] and never as
 * [KeyAction.CommitAction]: the latter routes through
 * `CommonKeyActionListener.commitAndReset()` + `commitText()`, which resets the input
 * context and writes the literal letter instead of feeding the engine.
 *
 * Implementation:
 * - [NineKeyAlphabetKey] carries `Behavior.Press(CommitAction(letters.first()))` purely
 *   as a press trigger; it also uses no popup, so a repeat tap reaches the key's click
 *   listener instead of being consumed as a popup trigger.
 * - [onAction] intercepts that trigger (only from [KeyActionListener.Source.Keyboard]),
 *   resolves the key through [firstLetterToDef], and owns cycling + the auto-commit timer.
 */
@SuppressLint("ViewConstructor")
class NineKeyKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "NineKey"

        /** Idle time (ms) before the pending letter is committed */
        private const val AUTO_COMMIT_DELAY = 500L

        /** Letter keys fill 3 of the 4 columns a spanned row has left. */
        private const val ALPHA_WIDTH = 0.27f

        /** The right-hand function column, narrower than a letter key. */
        private const val LAST_COLUMN_WIDTH = 0.19f

        /**
         * Width of each bottom-row key. Also used by the spanning punctuation key so its
         * right edge lines up with 符 below it. The space bar takes the remainder.
         */
        private const val BOTTOM_KEY_WIDTH = 0.1625f

        /**
         * Sogou-style 9-key layout.
         *
         * ```
         *   ，。？！   1    2ABC   3DEF   ⌫
         *   (spans)   4GHI  5JKL   6MNO   重输
         *   3 rows    7PQRS 8TUV   9WXYZ  0
         *   符        123   空格          中/英  ↵
         * ```
         *
         * The punctuation key down the left edge spans the three letter rows
         * ([KeyDef.rowSpan]); the letter rows then start to its right. Widths are
         * fractions of their row, except the spanning key and the bottom row, which are
         * fractions of the whole keyboard. `0f` lets the space bar absorb the slack.
         */
        val Layout: List<List<KeyDef>> = listOf(
            // Row 1
            listOf(
                NineKeyPunctuationKey(percentWidth = BOTTOM_KEY_WIDTH),
                NineKeyDigitKey("1", R.id.button_ninekey_digit_1, ALPHA_WIDTH),
                NineKeyAlphabetKey("ABC", "2", R.id.button_ninekey_alpha_2, ALPHA_WIDTH),
                NineKeyAlphabetKey("DEF", "3", R.id.button_ninekey_alpha_3, ALPHA_WIDTH),
                NineKeyBackspaceKey(LAST_COLUMN_WIDTH)
            ),
            // Row 2
            listOf(
                NineKeyAlphabetKey("GHI", "4", R.id.button_ninekey_alpha_4, ALPHA_WIDTH),
                NineKeyAlphabetKey("JKL", "5", R.id.button_ninekey_alpha_5, ALPHA_WIDTH),
                NineKeyAlphabetKey("MNO", "6", R.id.button_ninekey_alpha_6, ALPHA_WIDTH),
                NineKeyReinputKey(LAST_COLUMN_WIDTH)
            ),
            // Row 3
            listOf(
                NineKeyAlphabetKey("PQRS", "7", R.id.button_ninekey_alpha_7, ALPHA_WIDTH),
                NineKeyAlphabetKey("TUV", "8", R.id.button_ninekey_alpha_8, ALPHA_WIDTH),
                NineKeyAlphabetKey("WXYZ", "9", R.id.button_ninekey_alpha_9, ALPHA_WIDTH),
                NineKeyDigitKey("0", R.id.button_ninekey_digit_0, LAST_COLUMN_WIDTH)
            ),
            // Row 4
            listOf(
                NineKeySymbolKey("符", PickerWindow.Key.Symbol.name, BOTTOM_KEY_WIDTH),
                NineKeyNumberSwitchKey(BOTTOM_KEY_WIDTH),
                NineKeySpaceKey(),
                NineKeyLangSwitchKey(BOTTOM_KEY_WIDTH),
                NineKeyReturnKey(BOTTOM_KEY_WIDTH)
            )
        )

    }

    // ── Multi-tap state ────────────────────────────────────────────────────

    /** The currently pending alphabet key, or null if none */
    private var activeKeyDef: NineKeyAlphabetKey? = null
    /** View ID of the pending key, or -1 if none */
    private var activeViewId: Int = -1
    /** Index of the pending letter within the active key's combo */
    private var selectedLetterIndex: Int = 0

    /** Lookup: first letter → key def, built in [postInit] */
    private lateinit var firstLetterToDef: Map<Char, NineKeyAlphabetKey>

    /** The single tall punctuation key, resolved in [postInit] */
    private var punctKeyDef: NineKeyPunctuationKey? = null

    /** Localized punctuation mapping (ASCII → e.g. "." → "。"), display only */
    private var punctuationMapping: Map<String, String> = mapOf()

    private val handler = Handler(Looper.getMainLooper())
    private val autoCommitRunnable = Runnable { commitPending() }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun postInit() {
        // Keys are identified by their first letter; the eight alphabet keys have
        // distinct first letters (A D G J M P T W).
        firstLetterToDef = Layout.flatten()
            .filterIsInstance<NineKeyAlphabetKey>()
            .associateBy { it.letters.first() }
        punctKeyDef = Layout.flatten().filterIsInstance<NineKeyPunctuationKey>().firstOrNull()
    }

    override fun onDetach() {
        // Drop (do not commit) a pending letter: the multi-tap was never completed, and
        // a stray letter would land after the keyboard is already gone.
        resetState()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        // Mirror the full keyboard: surface the active input method on the space bar so
        // the user can tell whether the 9-key layout is driving pinyin or plain English.
        findViewById<TextKeyView>(R.id.button_ninekey_space)?.mainText?.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
    }

    /**
     * Localize the punctuation key — face and long-press menu — to match what fcitx5
     * will actually commit ("." → "。" in Chinese), mirroring [TextKeyboard]. Display
     * only: the press actions always send the ASCII character so fcitx5's punctuation
     * addon decides whether to convert it.
     */
    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        val symbols = punctKeyDef?.symbols ?: return
        findViewById<TextKeyView>(R.id.button_ninekey_punct)?.mainText?.text =
            symbols.joinToString("\n") { transformPunctuation(it) }
    }

    private fun transformPunctuation(p: String) =
        if (p.length == 1) punctuationMapping.getOrDefault(p, p) else p

    private fun localizePunctuationMenu(menu: KeyDef.Popup.Menu) = KeyDef.Popup.Menu(
        menu.items.map {
            KeyDef.Popup.Menu.Item(transformPunctuation(it.label), it.icon, it.action)
        }.toTypedArray()
    )

    override fun onPopupAction(action: PopupAction) {
        // Localize only the punctuation key's own labels; the pending-letter preview
        // must stay verbatim.
        val localized = if (action.viewId != R.id.button_ninekey_punct) action else when (action) {
            is PopupAction.PreviewAction ->
                action.copy(content = transformPunctuation(action.content))
            is PopupAction.PreviewUpdateAction ->
                action.copy(content = transformPunctuation(action.content))
            is PopupAction.ShowMenuAction -> action.copy(menu = localizePunctuationMenu(action.menu))
            else -> action
        }
        super.onPopupAction(localized)
    }

    /** Dismiss the pending preview (if any) and forget the pending key. */
    private fun resetState() {
        val viewId = activeViewId
        activeKeyDef = null
        activeViewId = -1
        selectedLetterIndex = 0
        handler.removeCallbacks(autoCommitRunnable)
        if (viewId != -1) {
            onPopupAction(PopupAction.DismissAction(viewId))
        }
    }

    // ── Press handling ─────────────────────────────────────────────────────

    override fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source
    ) {
        // The alphabet keys' press trigger, coming from the key itself (a popup commit
        // would carry a different action type and is forwarded below).
        if (action is KeyAction.CommitAction && source == KeyActionListener.Source.Keyboard) {
            val def = action.text.singleOrNull()?.let { firstLetterToDef[it] }
            val view = def?.let { findViewById<KeyView>(it.viewIdRes) }
            if (def != null && view != null) {
                onAlphabetPress(def, view)
                return
            }
        }
        // Any other key (backspace, space, return, layout switch, …) must not jump
        // ahead of a pending letter: commit it first so fcitx5 sees keys in order.
        commitPending()
        super.onAction(action, source)
    }

    private fun onAlphabetPress(def: NineKeyAlphabetKey, view: KeyView) {
        if (activeViewId == view.id) {
            // Same key tapped again → advance to the next letter
            selectedLetterIndex = (selectedLetterIndex + 1) % def.letters.length
        } else {
            // Different key → commit whatever is pending, then start fresh
            commitPending()
            activeKeyDef = def
            activeViewId = view.id
            selectedLetterIndex = 0
        }
        showPendingPreview(def, view)
        handler.removeCallbacks(autoCommitRunnable)
        handler.postDelayed(autoCommitRunnable, AUTO_COMMIT_DELAY)
    }

    /** Show the pending letter in a preview bubble above the key. */
    private fun showPendingPreview(def: NineKeyAlphabetKey, view: KeyView) {
        onPopupAction(
            PopupAction.PreviewAction(view.id, def.letters[selectedLetterIndex].toString(), view.bounds)
        )
    }

    /**
     * Commit the pending letter to fcitx5 (if any) and reset. Used by the auto-commit
     * timer and when the user moves on to a different key.
     */
    private fun commitPending() {
        val def = activeKeyDef ?: return
        val letter = def.letters.getOrNull(selectedLetterIndex) ?: return
        resetState()
        keyActionListener?.onKeyAction(letterKeyAction(letter), KeyActionListener.Source.Keyboard)
    }

    /**
     * [KeyAction.FcitxKeyAction] for an alphabet letter. The scancode is taken from the
     * uppercase form (only uppercase letters are mapped in `ScancodeMapping`) while the
     * character sent to fcitx5 is lowercased, mirroring [TextKeyboard].
     */
    private fun letterKeyAction(letter: Char) = KeyAction.FcitxKeyAction(
        letter.uppercaseChar().toString()
    ).copy(act = letter.lowercaseChar().toString())
}
