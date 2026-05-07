/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.IdRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.picker.PickerWindow

/**
 * 9-key alphabet key.
 * Shows the full [letters] combo on the key (e.g. "ABC").
 * - Long-press: shows popup menu with all letters (user selects)
 * - First tap: shows popup (no commit yet)
 * - 500ms timeout after last press: auto-commits first letter
 * - Popup selection: commits chosen letter
 *
 * Behavior.Press uses DeleteSelectionAction(0) as a no-op placeholder to
 * prevent immediate commit while still allowing the key to be "clicked".
 *
 * @param letters        Full letter combo shown on the key (e.g. "ABC")
 * @param digitHint      The digit shown as hint (e.g. "2" for ABC)
 * @param viewIdRes      Unique view ID for this key
 */
class NineKeyAlphabetKey(
    val letters: String,
    val digitHint: String,
    @IdRes val viewIdRes: Int = R.id.button_ninekey_alpha,
) : KeyDef(
    Appearance.Text(
        displayText = letters,
        textSize = 24f,
        percentWidth = 0f,  // fill remaining space equally
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = viewIdRes,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        // No-op placeholder — NineKeyKeyboard intercepts via onAction
        // and manages popup/commit manually. Using DeleteSelectionAction(0)
        // because it's handled as a no-op in CommonKeyActionListener.
        KeyDef.Behavior.Press(KeyAction.DeleteSelectionAction(0))
    ),
    popup = arrayOf(
        // Long-press shows menu with all letters for user to select
        KeyDef.Popup.Menu(
            items = letters.mapIndexed { idx, ch ->
                KeyDef.Popup.Menu.Item(
                    label = ch.toString(),
                    icon = 0,
                    action = KeyAction.CommitAction(ch.toString())
                )
            }.toTypedArray()
        )
    )
)

/**
 * 9-key punctuation key (top row . and ,)
 * @param primary   Primary symbol shown
 * @param secondary Secondary symbol in popup
 */
class NineKeyPunctKey(
    val primary: String,
    val secondary: String,
) : KeyDef(
    Appearance.Text(
        displayText = primary,
        textSize = 22f,
        percentWidth = 0f,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_punct,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    behaviors = setOf(
        KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(primary))
    ),
    popup = arrayOf(
        KeyDef.Popup.AltPreview(primary, secondary),
        KeyDef.Popup.Keyboard(primary)
    )
)

/**
 * 9-key space bar — longer than a normal key, shows "空格" label.
 */
class NineKeySpaceKey : KeyDef(
    Appearance.Text(
        displayText = "空格",
        textSize = 14f,
        percentWidth = 0f,  // fill remaining space
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
        )
    ),
    popup = null
)

/**
 * 9-key backspace — same as standard BackspaceKey but with 9-key viewId.
 */
class NineKeyBackspaceKey(
    percentWidth: Float = 0.2f,
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
    percentWidth: Float = 0.2f
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
 * 9-key language / layout switch key.
 */
class NineKeyLayoutSwitchKey(
    displayText: String,
    val to: String = "",
    percentWidth: Float = 0.2f,
    variant: KeyDef.Appearance.Variant = KeyDef.Appearance.Variant.Alternative
) : KeyDef(
    Appearance.Text(
        displayText = displayText,
        textSize = 14f,
        textStyle = Typeface.BOLD,
        percentWidth = percentWidth,
        variant = variant,
        border = KeyDef.Appearance.Border.Default,
        margin = true,
        viewId = R.id.button_ninekey_switch,
        soundEffect = InputFeedbacks.SoundEffect.Standard
    ),
    setOf(
        KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(to))
    )
)

/**
 * 9-key symbol key (shown as "符").
 */
class NineKeySymbolKey(
    displayText: String = "符",
    to: String = PickerWindow.Key.Symbol.name,
    percentWidth: Float = 0.2f
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
