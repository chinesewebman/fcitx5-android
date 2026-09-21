/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.IdRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.picker.PickerWindow

/**
 * 9-key alphabet key — `ABC`/`DEF`/… with the T9 digit as its small alt label, the same
 * idiom the full keyboard uses for `Q`/`1`.
 *
 * The letter is chosen by multi-tap: [NineKeyKeyboard] advances through [letters] on
 * every press and commits the pending letter to fcitx5 after a short idle. There is
 * deliberately no popup menu — a menu would swallow the next tap (see
 * `CustomGestureView`'s consumed-gesture handling) and break cycling.
 *
 * [Behavior.Press] carries a [KeyAction.CommitAction] holding the key's first letter as
 * a press *trigger* only; [NineKeyKeyboard.onAction] intercepts it to identify the key
 * and never commits that action as-is.
 *
 * @param letters        Full letter combo shown on the key (e.g. "ABC", "DEF")
 * @param digit          T9 digit shown as the alt label (e.g. "2" for ABC)
 * @param viewIdRes      Unique view ID for this key
 * @param percentWidth   Width as a fraction of its row
 */
class NineKeyAlphabetKey(
    val letters: String,
    val digit: String,
    @IdRes val viewIdRes: Int = R.id.button_ninekey_alpha,
    percentWidth: Float = 1f / 3f,
) : KeyDef(
    Appearance.AltText(
        displayText = letters,
        altText = digit,
        textSize = 20f,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = viewIdRes
    ),
    behaviors = setOf(
        // Press trigger only — intercepted in NineKeyKeyboard.onAction(CommitAction).
        KeyDef.Behavior.Press(KeyAction.CommitAction(letters.first().toString()))
    )
)

/**
 * Tall punctuation key down the left edge, spanning the three letter rows and showing
 * its symbols stacked (`，` `。` `？` `！`) — the Sogou 9-key arrangement.
 *
 * A tap commits [symbols]`[0]`; the remaining symbols are on a long-press menu. All of
 * them are sent as their ASCII form so fcitx5's punctuation addon decides whether to
 * map them to full-width Chinese punctuation, exactly like the full keyboard's keys.
 *
 * @param symbols       Punctuation as ASCII, top to bottom on the key face
 * @param percentWidth  Width as a fraction of the whole keyboard (it spans rows, so it
 *                      is measured against the keyboard rather than a row)
 */
class NineKeyPunctuationKey(
    val symbols: List<String> = listOf(",", ".", "?", "!"),
    percentWidth: Float = 3f / 20f,
) : KeyDef(
    Appearance.Text(
        displayText = symbols.first(),
        lines = symbols,
        textSize = 15f,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_punct,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(symbols.first()))
    ),
    popup = arrayOf(
        KeyDef.Popup.Menu(
            items = symbols.drop(1).map { s ->
                KeyDef.Popup.Menu.Item(
                    label = s,
                    icon = R.drawable.ic_baseline_keyboard_24,
                    action = KeyAction.FcitxKeyAction(s)
                )
            }.toTypedArray()
        )
    ),
    // Rows 1-3: the key is pinned to the keyboard and the letter rows start to its right.
    rowSpan = 3
)

/**
 * A plain digit key (the `1` and `0` slots of the T9 grid).
 *
 * Sent as [KeyAction.FcitxKeyAction] rather than a direct commit so the daemon can
 * still use it as a candidate selector while composing.
 */
class NineKeyDigitKey(
    val digit: String,
    @IdRes viewIdRes: Int,
    percentWidth: Float = 1f / 3f,
) : KeyDef(
    Appearance.Text(
        displayText = digit,
        textSize = 20f,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = viewIdRes,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(digit))
    )
)

/**
 * 重输 — discard the in-progress pinyin and start over, without touching committed text.
 */
class NineKeyReinputKey(
    percentWidth: Float = 1f / 4f,
) : KeyDef(
    Appearance.Text(
        displayText = "重输",
        textSize = 14f,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_reinput,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.ResetInputAction)
    )
)

/**
 * 中/英 — switch input method from the 9-key layout, which has no dedicated language
 * key. Long press opens the input method picker, mirroring the full keyboard's key.
 */
class NineKeyLangSwitchKey(
    percentWidth: Float = 1f / 6f,
) : KeyDef(
    Appearance.Text(
        displayText = "中/英",
        textSize = 14f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_lang,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.LangSwitchAction),
        KeyDef.Behavior.LongPress(KeyAction.ShowInputMethodPickerAction)
    )
)

/**
 * 123 — the numeric pad.
 */
class NineKeyNumberSwitchKey(
    percentWidth: Float = 1f / 6f,
) : KeyDef(
    Appearance.Text(
        displayText = "123",
        textSize = 14f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_number,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(NumberKeyboard.Name))
    )
)

/**
 * 9-key space bar — fills whatever width its row has left, shows the active input
 * method's name (set by [NineKeyKeyboard.onInputMethodUpdate]).
 *
 * Long press mirrors the full keyboard's space key: it triggers
 * [KeyAction.SpaceLongPressAction], which the user can map to input method
 * enumeration / toggle / picker in settings.
 */
class NineKeySpaceKey : KeyDef(
    Appearance.Text(
        displayText = "空格",
        textSize = 14f,
        percentWidth = 0f,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Special,
        margin = true,
        viewId = R.id.button_ninekey_space,
        soundEffect = InputFeedbacks.SoundEffect.SpaceBar
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(
            KeyAction.SymAction(
                KeySym(FcitxKeyMapping.FcitxKey_space),
                KeyStates.Virtual
            )
        ),
        KeyDef.Behavior.LongPress(KeyAction.SpaceLongPressAction)
    ),
    popup = null
)

/**
 * 9-key backspace, with repeat-on-hold.
 */
class NineKeyBackspaceKey(
    percentWidth: Float = 1f / 4f,
    variant: KeyDef.Appearance.Variant = KeyDef.Appearance.Variant.Alternative
) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        percentWidth = percentWidth,
        variant = variant,
        viewId = R.id.button_backspace,
        soundEffect = InputFeedbacks.SoundEffect.Delete
    ),
    setOf(
        KeyDef.Behavior.Press(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
        ),
        KeyDef.Behavior.Repeat(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
        )
    )
)

/**
 * 9-key return key.
 */
class NineKeyReturnKey(
    percentWidth: Float = 1f / 6f
) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_keyboard_return_24,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Accent,
        border = KeyDef.Appearance.Border.Special,
        viewId = R.id.button_return,
        soundEffect = InputFeedbacks.SoundEffect.Return
    ),
    setOf(
        KeyDef.Behavior.Press(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))
        )
    )
)

/**
 * 9-key symbol key (符).
 */
class NineKeySymbolKey(
    displayText: String = "符",
    to: String = PickerWindow.Key.Symbol.name,
    percentWidth: Float = 1f / 6f
) : KeyDef(
    Appearance.Text(
        displayText = displayText,
        textSize = 14f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = KeyDef.Appearance.Variant.Alternative,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_sym,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    setOf(
        KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(to))
    )
)
