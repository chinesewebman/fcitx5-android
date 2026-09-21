/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction

/**
 * 9-key (T9 / 九宫格) keyboard layout, modelled on Sogou's current behaviour.
 *
 * Each letter key press emits the **first letter of its combo immediately** through
 * [KeyAction.FcitxKeyAction], which routes through to fcitx5's input method. fcitx5's
 * pinyin engine (or any other IM with a spelling engine) accumulates the ambiguous
 * stream — for example "MNO MNO GHI" typed fast becomes "nni" in the preedit — and
 * resolves the candidate list against its dictionary. The user does not cycle a key
 * to pick the second letter; that ambiguity is the spelling engine's job. Tapping
 * the same key twice really does mean two letters (e.g. "mm" or "hh").
 *
 * Layout:
 * ```
 *   ，。？！   1    2ABC   3DEF   ⌫
 *   (spans)   4GHI  5JKL   6MNO   重输
 *   3 rows    7PQRS 8TUV   9WXYZ  0
 *   符        123   空格           中/英  ↵
 * ```
 */
@SuppressLint("ViewConstructor")
class NineKeyKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "NineKey"

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

    /** The single tall punctuation key. */
    private val punctKeyDef: NineKeyPunctuationKey? =
        Layout.flatten().filterIsInstance<NineKeyPunctuationKey>().firstOrNull()

    /** Localized punctuation mapping (ASCII → e.g. "." → "。"), display only */
    private var punctuationMapping: Map<String, String> = mapOf()

    override fun onDetach() {
        // Dismiss the punctuation menu popup if it was open, so it does not leak across
        // keyboard switches.
        onPopupAction(PopupAction.DismissAction(R.id.button_ninekey_punct))
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
        val view = findViewById<TextKeyView>(R.id.button_ninekey_punct) ?: return
        if (view.stackedTexts.isEmpty()) {
            view.mainText.text = transformPunctuation(symbols.first())
        } else {
            view.stackedTexts.forEachIndexed { i, tv ->
                tv.text = transformPunctuation(symbols.getOrElse(i) { "" })
            }
        }
    }

    private fun transformPunctuation(p: String) =
        if (p.length == 1) punctuationMapping.getOrDefault(p, p) else p

    private fun localizePunctuationMenu(menu: KeyDef.Popup.Menu) = KeyDef.Popup.Menu(
        menu.items.map {
            KeyDef.Popup.Menu.Item(transformPunctuation(it.label), it.icon, it.action)
        }.toTypedArray()
    )

    override fun onPopupAction(action: PopupAction) {
        // Localize only the punctuation key's own labels.
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
}
