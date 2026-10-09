package com.minibrain.ui.theme

import android.app.UiModeManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ライト / ダークの選び方。SYSTEM は端末の設定に従う。 */
enum class ThemeMode(val uiModeNight: Int) {
    SYSTEM(UiModeManager.MODE_NIGHT_AUTO),
    LIGHT(UiModeManager.MODE_NIGHT_NO),
    DARK(UiModeManager.MODE_NIGHT_YES);

    companion object {
        val DEFAULT = SYSTEM

        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

private val PREF_THEME_MODE = stringPreferencesKey("theme_mode")

fun DataStore<Preferences>.themeModeFlow(): Flow<ThemeMode> =
    data.map { ThemeMode.fromName(it.asMap()[PREF_THEME_MODE] as? String) }

/**
 * 選択を保存し、アプリのナイトモードを切り替える。`setApplicationNightMode`（API 31+）は
 * システム側に保存され、`values-night` のリソースと `isSystemInDarkTheme` の両方に効く。
 * 起動時に掛け直す必要は無い。DataStore の値は設定画面で今の選択を出すためだけに持つ。
 */
suspend fun DataStore<Preferences>.setThemeMode(context: Context, mode: ThemeMode) {
    edit { it[PREF_THEME_MODE] = mode.name }
    (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
        ?.setApplicationNightMode(mode.uiModeNight)
}
