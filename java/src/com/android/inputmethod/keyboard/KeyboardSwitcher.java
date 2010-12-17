/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.keyboard;

import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.Settings;
import com.android.inputmethod.latin.Utils;
import com.android.inputmethod.latin.LatinImeLogger;
import com.android.inputmethod.latin.R;
import com.android.inputmethod.latin.SubtypeSwitcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import android.view.InflateException;
import android.view.inputmethod.InputMethodManager;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Locale;

public class KeyboardSwitcher implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "KeyboardSwitcher";
    private static final boolean DEBUG = false;
    public static final boolean DEBUG_STATE = false;

    // Changing DEFAULT_LAYOUT_ID also requires prefs_for_debug.xml to be matched with.
    public static final String DEFAULT_LAYOUT_ID = "5";
    public static final String PREF_KEYBOARD_LAYOUT = "pref_keyboard_layout_20100902";
    private static final int[] THEMES = new int [] {
        R.layout.input_basic,
        R.layout.input_basic_highcontrast,
        R.layout.input_stone_normal,
        R.layout.input_stone_bold,
        R.layout.input_gingerbread,
        R.layout.input_honeycomb, // DEFAULT_LAYOUT_ID
    };

    private static final int SYMBOLS_MODE_STATE_NONE = 0;
    private static final int SYMBOLS_MODE_STATE_BEGIN = 1;
    private static final int SYMBOLS_MODE_STATE_SYMBOL = 2;

    private SubtypeSwitcher mSubtypeSwitcher;
    private SharedPreferences mPrefs;

    private LatinKeyboardView mInputView;
    private LatinIME mInputMethodService;

    private ShiftKeyState mShiftKeyState = new ShiftKeyState("Shift");
    private ModifierKeyState mSymbolKeyState = new ModifierKeyState("Symbol");

    private KeyboardId mSymbolsId;
    private KeyboardId mSymbolsShiftedId;

    private KeyboardId mCurrentId;
    private final HashMap<KeyboardId, SoftReference<LatinKeyboard>> mKeyboardCache =
            new HashMap<KeyboardId, SoftReference<LatinKeyboard>>();

    private int mMode = KeyboardId.MODE_TEXT; /* default value */
    private int mImeOptions;
    private boolean mIsSymbols;
    /** mIsAutoCorrectionActive indicates that auto corrected word will be input instead of
     * what user actually typed. */
    private boolean mIsAutoCorrectionActive;
    private boolean mVoiceKeyEnabled;
    private boolean mVoiceButtonOnPrimary;
    private int mSymbolsModeState = SYMBOLS_MODE_STATE_NONE;

    // Indicates whether or not we have the settings key
    private boolean mHasSettingsKey;
    private static final int SETTINGS_KEY_MODE_AUTO = R.string.settings_key_mode_auto;
    private static final int SETTINGS_KEY_MODE_ALWAYS_SHOW =
            R.string.settings_key_mode_always_show;
    // NOTE: No need to have SETTINGS_KEY_MODE_ALWAYS_HIDE here because it's not being referred to
    // in the source code now.
    // Default is SETTINGS_KEY_MODE_AUTO.
    private static final int DEFAULT_SETTINGS_KEY_MODE = SETTINGS_KEY_MODE_AUTO;

    private int mLayoutId;

    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(LatinIME ims, SharedPreferences prefs) {
        sInstance.mInputMethodService = ims;
        sInstance.mPrefs = prefs;
        sInstance.mSubtypeSwitcher = SubtypeSwitcher.getInstance();

        sInstance.mLayoutId = Integer.valueOf(
                prefs.getString(PREF_KEYBOARD_LAYOUT, DEFAULT_LAYOUT_ID));
        prefs.registerOnSharedPreferenceChangeListener(sInstance);
    }

    private void makeSymbolsKeyboardIds() {
        final Locale locale = mSubtypeSwitcher.getInputLocale();
        final int orientation = mInputMethodService.getResources().getConfiguration().orientation;
        final int mode = mMode;
        final int colorScheme = getColorScheme();
        final boolean hasSettingsKey = mHasSettingsKey;
        final boolean voiceKeyEnabled = mVoiceKeyEnabled;
        final boolean hasVoiceKey = voiceKeyEnabled && !mVoiceButtonOnPrimary;
        final int imeOptions = mImeOptions;
        // Note: This comment is only applied for phone number keyboard layout.
        // On non-xlarge device, "@integer/key_switch_alpha_symbol" key code is used to switch
        // between "phone keyboard" and "phone symbols keyboard".  But on xlarge device,
        // "@integer/key_shift" key code is used for that purpose in order to properly display
        // "more" and "locked more" key labels.  To achieve these behavior, we should initialize
        // mSymbolsId and mSymbolsShiftedId to "phone keyboard" and "phone symbols keyboard"
        // respectively here for xlarge device's layout switching.
        mSymbolsId = new KeyboardId(locale, orientation, mode,
                mode == KeyboardId.MODE_PHONE ? R.xml.kbd_phone : R.xml.kbd_symbols,
                colorScheme, hasSettingsKey, voiceKeyEnabled, hasVoiceKey, imeOptions, true);
        mSymbolsShiftedId = new KeyboardId(locale, orientation, mode,
                mode == KeyboardId.MODE_PHONE ? R.xml.kbd_phone_symbols : R.xml.kbd_symbols_shift,
                colorScheme, hasSettingsKey, voiceKeyEnabled, hasVoiceKey, imeOptions, true);
    }

    private boolean hasVoiceKey(boolean isSymbols) {
        return mVoiceKeyEnabled && (isSymbols != mVoiceButtonOnPrimary);
    }

    public void loadKeyboard(int mode, int imeOptions, boolean voiceKeyEnabled,
            boolean voiceButtonOnPrimary) {
        mSymbolsModeState = SYMBOLS_MODE_STATE_NONE;
        try {
            loadKeyboardInternal(mode, imeOptions, voiceKeyEnabled, voiceButtonOnPrimary,
                    false);
        } catch (RuntimeException e) {
            Log.w(TAG, e);
            LatinImeLogger.logOnException(mode + "," + imeOptions, e);
        }
    }

    private void loadKeyboardInternal(int mode, int imeOptions, boolean voiceButtonEnabled,
            boolean voiceButtonOnPrimary, boolean isSymbols) {
        if (mInputView == null) return;
        mInputView.setPreviewEnabled(mInputMethodService.getPopupOn());

        mMode = mode;
        mImeOptions = imeOptions;
        mVoiceKeyEnabled = voiceButtonEnabled;
        mVoiceButtonOnPrimary = voiceButtonOnPrimary;
        mIsSymbols = isSymbols;
        // Update the settings key state because number of enabled IMEs could have been changed
        mHasSettingsKey = getSettingsKeyMode(mPrefs, mInputMethodService);
        makeSymbolsKeyboardIds();

        KeyboardId id = getKeyboardId(mode, imeOptions, isSymbols);
        LatinKeyboard keyboard = getKeyboard(id);

        mCurrentId = id;
        mInputView.setKeyboard(keyboard);
    }

    private LatinKeyboard getKeyboard(KeyboardId id) {
        final SoftReference<LatinKeyboard> ref = mKeyboardCache.get(id);
        LatinKeyboard keyboard = (ref == null) ? null : ref.get();
        if (keyboard == null) {
            final Locale savedLocale =  mSubtypeSwitcher.changeSystemLocale(
                    mSubtypeSwitcher.getInputLocale());

            keyboard = new LatinKeyboard(mInputMethodService, id);

            if (id.mEnableShiftLock) {
                keyboard.enableShiftLock();
            }

            mKeyboardCache.put(id, new SoftReference<LatinKeyboard>(keyboard));
            if (DEBUG)
                Log.d(TAG, "keyboard cache size=" + mKeyboardCache.size() + ": "
                        + ((ref == null) ? "LOAD" : "GCed") + " id=" + id);

            mSubtypeSwitcher.changeSystemLocale(savedLocale);
        } else if (DEBUG) {
            Log.d(TAG, "keyboard cache size=" + mKeyboardCache.size() + ": HIT  id=" + id);
        }

        keyboard.onAutoCorrectionStateChanged(mIsAutoCorrectionActive);
        keyboard.setShifted(false);
        return keyboard;
    }

    private KeyboardId getKeyboardId(int mode, int imeOptions, boolean isSymbols) {
        final boolean hasVoiceKey = hasVoiceKey(isSymbols);
        final int charColorId = getColorScheme();
        final int xmlId;
        final boolean enableShiftLock;

        if (isSymbols) {
            if (mode == KeyboardId.MODE_PHONE) {
                xmlId = R.xml.kbd_phone_symbols;
            } else if (mode == KeyboardId.MODE_NUMBER) {
                // Note: MODE_NUMBER keyboard layout has no "switch alpha symbol" key.
                xmlId = R.xml.kbd_number;
            } else {
                xmlId = R.xml.kbd_symbols;
            }
            enableShiftLock = false;
        } else {
            if (mode == KeyboardId.MODE_PHONE) {
                xmlId = R.xml.kbd_phone;
                enableShiftLock = false;
            } else if (mode == KeyboardId.MODE_NUMBER) {
                xmlId = R.xml.kbd_number;
                enableShiftLock = false;
            } else {
                xmlId = R.xml.kbd_qwerty;
                enableShiftLock = true;
            }
        }
        final int orientation = mInputMethodService.getResources().getConfiguration().orientation;
        final Locale locale = mSubtypeSwitcher.getInputLocale();
        return new KeyboardId(locale, orientation, mode, xmlId, charColorId,
                mHasSettingsKey, mVoiceKeyEnabled, hasVoiceKey, imeOptions, enableShiftLock);
    }

    public int getKeyboardMode() {
        return mMode;
    }

    public boolean isAlphabetMode() {
        return mCurrentId != null && mCurrentId.isAlphabetKeyboard();
    }

    public boolean isInputViewShown() {
        return mInputView != null && mInputView.isShown();
    }

    public boolean isKeyboardAvailable() {
        if (mInputView != null)
            return mInputView.getLatinKeyboard() != null;
        return false;
    }

    private LatinKeyboard getLatinKeyboard() {
        if (mInputView != null)
            return mInputView.getLatinKeyboard();
        return null;
    }

    public void setPreferredLetters(int[] frequencies) {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            latinKeyboard.setPreferredLetters(frequencies);
    }

    public void keyReleased() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            latinKeyboard.keyReleased();
    }

    public boolean isShiftedOrShiftLocked() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            return latinKeyboard.isShiftedOrShiftLocked();
        return false;
    }

    public boolean isShiftLocked() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            return latinKeyboard.isShiftLocked();
        return false;
    }

    public boolean isAutomaticTemporaryUpperCase() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            return latinKeyboard.isAutomaticTemporaryUpperCase();
        return false;
    }

    public boolean isManualTemporaryUpperCase() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null)
            return latinKeyboard.isManualTemporaryUpperCase();
        return false;
    }

    private void setManualTemporaryUpperCase(boolean shifted) {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null) {
            // On non-distinct multi touch panel device, we should also turn off the shift locked
            // state when shift key is pressed to go to normal mode.
            // On the other hand, on distinct multi touch panel device, turning off the shift locked
            // state with shift key pressing is handled by onReleaseShift().
            if (!hasDistinctMultitouch() && !shifted && latinKeyboard.isShiftLocked()) {
                latinKeyboard.setShiftLocked(false);
            }
            if (latinKeyboard.setShifted(shifted)) {
                mInputView.invalidateAllKeys();
            }
        }
    }

    private void setShiftLocked(boolean shiftLocked) {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null && latinKeyboard.setShiftLocked(shiftLocked)) {
            mInputView.invalidateAllKeys();
        }
    }

    /**
     * Toggle keyboard shift state triggered by user touch event.
     */
    public void toggleShift() {
        mInputMethodService.mHandler.cancelUpdateShiftState();
        if (DEBUG_STATE)
            Log.d(TAG, "toggleShift:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + mShiftKeyState);
        if (isAlphabetMode()) {
            setManualTemporaryUpperCase(!isShiftedOrShiftLocked());
        } else {
            toggleShiftInSymbol();
        }
    }

    public void toggleCapsLock() {
        mInputMethodService.mHandler.cancelUpdateShiftState();
        if (DEBUG_STATE)
            Log.d(TAG, "toggleCapsLock:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + mShiftKeyState);
        if (isAlphabetMode()) {
            if (isShiftLocked()) {
                // Shift key is long pressed while caps lock state, we will toggle back to normal
                // state. And mark as if shift key is released.
                setShiftLocked(false);
                mShiftKeyState.onRelease();
            } else {
                setShiftLocked(true);
            }
        }
    }

    private void setAutomaticTemporaryUpperCase() {
        LatinKeyboard latinKeyboard = getLatinKeyboard();
        if (latinKeyboard != null) {
            latinKeyboard.setAutomaticTemporaryUpperCase();
            mInputView.invalidateAllKeys();
        }
    }

    /**
     * Update keyboard shift state triggered by connected EditText status change.
     */
    public void updateShiftState() {
        final ShiftKeyState shiftKeyState = mShiftKeyState;
        if (DEBUG_STATE)
            Log.d(TAG, "updateShiftState:"
                    + " autoCaps=" + mInputMethodService.getCurrentAutoCapsState()
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + shiftKeyState);
        if (isAlphabetMode()) {
            if (!isShiftLocked() && !shiftKeyState.isIgnoring()) {
                if (shiftKeyState.isReleasing() && mInputMethodService.getCurrentAutoCapsState()) {
                    // Only when shift key is releasing, automatic temporary upper case will be set.
                    setAutomaticTemporaryUpperCase();
                } else {
                    setManualTemporaryUpperCase(shiftKeyState.isMomentary());
                }
            }
        } else {
            // In symbol keyboard mode, we should clear shift key state because only alphabet
            // keyboard has shift key.
            shiftKeyState.onRelease();
        }
    }

    public void changeKeyboardMode() {
        if (DEBUG_STATE)
            Log.d(TAG, "changeKeyboardMode:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + mShiftKeyState);
        toggleKeyboardMode();
        if (isShiftLocked() && isAlphabetMode())
            setShiftLocked(true);
        updateShiftState();
    }

    public void onPressShift() {
        if (!isKeyboardAvailable())
            return;
        ShiftKeyState shiftKeyState = mShiftKeyState;
        if (DEBUG_STATE)
            Log.d(TAG, "onPressShift:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + shiftKeyState);
        if (isAlphabetMode()) {
            if (isShiftLocked()) {
                // Shift key is pressed while caps lock state, we will treat this state as shifted
                // caps lock state and mark as if shift key pressed while normal state.
                shiftKeyState.onPress();
                setManualTemporaryUpperCase(true);
            } else if (isAutomaticTemporaryUpperCase()) {
                // Shift key is pressed while automatic temporary upper case, we have to move to
                // manual temporary upper case.
                shiftKeyState.onPress();
                setManualTemporaryUpperCase(true);
            } else if (isShiftedOrShiftLocked()) {
                // In manual upper case state, we just record shift key has been pressing while
                // shifted state.
                shiftKeyState.onPressOnShifted();
            } else {
                // In base layout, chording or manual temporary upper case mode is started.
                shiftKeyState.onPress();
                toggleShift();
            }
        } else {
            // In symbol mode, just toggle symbol and symbol more keyboard.
            shiftKeyState.onPress();
            toggleShift();
        }
    }

    public void onReleaseShift() {
        if (!isKeyboardAvailable())
            return;
        ShiftKeyState shiftKeyState = mShiftKeyState;
        if (DEBUG_STATE)
            Log.d(TAG, "onReleaseShift:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + shiftKeyState);
        if (isAlphabetMode()) {
            if (shiftKeyState.isMomentary()) {
                // After chording input while normal state.
                toggleShift();
            } else if (isShiftLocked() && !shiftKeyState.isIgnoring()) {
                // Shift has been pressed without chording while caps lock state.
                toggleCapsLock();
            } else if (isShiftedOrShiftLocked() && shiftKeyState.isPressingOnShifted()) {
                // Shift has been pressed without chording while shifted state.
                toggleShift();
            }
        }
        shiftKeyState.onRelease();
    }

    public void onPressSymbol() {
        if (DEBUG_STATE)
            Log.d(TAG, "onReleaseShift:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " symbolKeyState=" + mSymbolKeyState);
        changeKeyboardMode();
        mSymbolKeyState.onPress();
    }

    public void onReleaseSymbol() {
        if (DEBUG_STATE)
            Log.d(TAG, "onReleaseShift:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " symbolKeyState=" + mSymbolKeyState);
        if (mSymbolKeyState.isMomentary())
            changeKeyboardMode();
        mSymbolKeyState.onRelease();
    }

    public void onOtherKeyPressed() {
        if (DEBUG_STATE)
            Log.d(TAG, "onOtherKeyPressed:"
                    + " keyboard=" + getLatinKeyboard().getKeyboardShiftState()
                    + " shiftKeyState=" + mShiftKeyState
                    + " symbolKeyState=" + mSymbolKeyState);
        mShiftKeyState.onOtherKeyPressed();
        mSymbolKeyState.onOtherKeyPressed();
    }

    private void toggleShiftInSymbol() {
        if (isAlphabetMode())
            return;
        final LatinKeyboard keyboard;
        if (mCurrentId.equals(mSymbolsId) || !mCurrentId.equals(mSymbolsShiftedId)) {
            mCurrentId = mSymbolsShiftedId;
            keyboard = getKeyboard(mCurrentId);
            // Symbol shifted keyboard has an ALT key that has a caps lock style indicator. To
            // enable the indicator, we need to call enableShiftLock() and setShiftLocked(true).
            // Thus we can keep the ALT key's Key.on value true while LatinKey.onRelease() is
            // called.
            keyboard.setShiftLocked(true);
        } else {
            mCurrentId = mSymbolsId;
            keyboard = getKeyboard(mCurrentId);
            // Symbol keyboard has an ALT key that has a caps lock style indicator. To disable the
            // indicator, we need to call enableShiftLock() and setShiftLocked(false).
            keyboard.setShifted(false);
        }
        mInputView.setKeyboard(keyboard);
    }

    private void toggleKeyboardMode() {
        loadKeyboardInternal(mMode, mImeOptions, mVoiceKeyEnabled, mVoiceButtonOnPrimary,
                !mIsSymbols);
        if (mIsSymbols) {
            mSymbolsModeState = SYMBOLS_MODE_STATE_BEGIN;
        } else {
            mSymbolsModeState = SYMBOLS_MODE_STATE_NONE;
        }
    }

    public boolean hasDistinctMultitouch() {
        return mInputView != null && mInputView.hasDistinctMultitouch();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to alpha mode.
     */
    public void onKey(int key) {
        // Switch back to alpha mode if user types one or more non-space/enter
        // characters followed by a space/enter
        switch (mSymbolsModeState) {
        case SYMBOLS_MODE_STATE_BEGIN:
            if (key != Keyboard.CODE_SPACE && key != Keyboard.CODE_ENTER && key > 0) {
                mSymbolsModeState = SYMBOLS_MODE_STATE_SYMBOL;
            }
            break;
        case SYMBOLS_MODE_STATE_SYMBOL:
            if (key == Keyboard.CODE_ENTER || key == Keyboard.CODE_SPACE) {
                changeKeyboardMode();
            }
            break;
        }
    }

    public LatinKeyboardView getInputView() {
        return mInputView;
    }

    public LatinKeyboardView onCreateInputView() {
        createInputViewInternal(mLayoutId, true);
        return mInputView;
    }

    private void createInputViewInternal(int newLayout, boolean forceReset) {
        int layoutId = newLayout;
        if (mLayoutId != layoutId || mInputView == null || forceReset) {
            if (mInputView != null) {
                mInputView.closing();
            }
            if (THEMES.length <= layoutId) {
                layoutId = Integer.valueOf(DEFAULT_LAYOUT_ID);
            }

            Utils.GCUtils.getInstance().reset();
            boolean tryGC = true;
            for (int i = 0; i < Utils.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
                try {
                    mInputView = (LatinKeyboardView) mInputMethodService.getLayoutInflater(
                            ).inflate(THEMES[layoutId], null);
                    tryGC = false;
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "load keyboard failed: " + e);
                    tryGC = Utils.GCUtils.getInstance().tryGCOrWait(
                            mLayoutId + "," + layoutId, e);
                } catch (InflateException e) {
                    Log.w(TAG, "load keyboard failed: " + e);
                    tryGC = Utils.GCUtils.getInstance().tryGCOrWait(
                            mLayoutId + "," + layoutId, e);
                }
            }
            mInputView.setOnKeyboardActionListener(mInputMethodService);
            mLayoutId = layoutId;
        }
    }

    private void postSetInputView() {
        mInputMethodService.mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mInputView != null) {
                    mInputMethodService.setInputView(mInputView);
                }
                mInputMethodService.updateInputViewShown();
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREF_KEYBOARD_LAYOUT.equals(key)) {
            final int layoutId = Integer.valueOf(
                    sharedPreferences.getString(key, DEFAULT_LAYOUT_ID));
            createInputViewInternal(layoutId, false);
            postSetInputView();
        } else if (Settings.PREF_SETTINGS_KEY.equals(key)) {
            mHasSettingsKey = getSettingsKeyMode(sharedPreferences, mInputMethodService);
            createInputViewInternal(mLayoutId, true);
            postSetInputView();
        }
    }

    private int getColorScheme() {
        return (mInputView != null)
                ? mInputView.getColorScheme() : KeyboardView.COLOR_SCHEME_WHITE;
    }

    public void onAutoCorrectionStateChanged(boolean isAutoCorrection) {
        if (isAutoCorrection != mIsAutoCorrectionActive) {
            LatinKeyboardView keyboardView = getInputView();
            mIsAutoCorrectionActive = isAutoCorrection;
            keyboardView.invalidateKey(((LatinKeyboard) keyboardView.getKeyboard())
                    .onAutoCorrectionStateChanged(isAutoCorrection));
        }
    }

    private static boolean getSettingsKeyMode(SharedPreferences prefs, Context context) {
        Resources resources = context.getResources();
        final boolean showSettingsKeyOption = resources.getBoolean(
                R.bool.config_enable_show_settings_key_option);
        if (showSettingsKeyOption) {
            final String settingsKeyMode = prefs.getString(Settings.PREF_SETTINGS_KEY,
                    resources.getString(DEFAULT_SETTINGS_KEY_MODE));
            // We show the settings key when 1) SETTINGS_KEY_MODE_ALWAYS_SHOW or
            // 2) SETTINGS_KEY_MODE_AUTO and there are two or more enabled IMEs on the system
            if (settingsKeyMode.equals(resources.getString(SETTINGS_KEY_MODE_ALWAYS_SHOW))
                    || (settingsKeyMode.equals(resources.getString(SETTINGS_KEY_MODE_AUTO))
                            && Utils.hasMultipleEnabledIMEsOrSubtypes(
                                    ((InputMethodManager) context.getSystemService(
                                            Context.INPUT_METHOD_SERVICE))))) {
                return true;
            }
        }
        return false;
    }
}