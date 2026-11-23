// SettingsViewModel.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.app.Application
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fct.tc4.R
import com.fct.tc4.ui.misc.Global
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ===================== Data Models =====================

sealed class SettingsItem {
    abstract val id: String

    @get:StringRes
    abstract val titleRes: Int

    @get:StringRes
    abstract val descriptionRes: Int
}

/** 开关型设置项 */
data class SwitchSetting(
    override val id: String,
    @StringRes override val titleRes: Int,
    @StringRes override val descriptionRes: Int,
    val isChecked: Boolean
) : SettingsItem()

/** 动作型设置项（点击执行跳转/请求，无开关） */
data class ActionSetting(
    override val id: String,
    @StringRes override val titleRes: Int,
    @StringRes override val descriptionRes: Int,
    val isGranted: Boolean = false
) : SettingsItem()

// ===================== ViewModel =====================

class SettingsViewModel(
    application: Application,
    private val code: String
) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<SettingsItem>>(emptyList())
    val items: StateFlow<List<SettingsItem>> = _items.asStateFlow()

    private val x11Prefs by lazy {
        val app = getApplication<Application>()
        app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
    }

    init { refresh() }

    /** 切换开关状态并持久化 */
    fun toggle(item: SwitchSetting) {
        val newChecked = !item.isChecked
        when (item.id) {
            "x11_touch_direct" -> x11Prefs.edit().putString("touchMode", if (newChecked) "2" else "1").apply()
            "x11_hide_cutout" -> x11Prefs.edit().putBoolean("hideCutout", newChecked).apply()
            "x11_fullscreen" -> x11Prefs.edit()
                .putBoolean("fullscreen", newChecked)
                .putBoolean("keepScreenOn", newChecked)
                .apply()
            "x11_ekbar" -> x11Prefs.edit()
                .putBoolean("showAdditionalKbd", newChecked)
                .putBoolean("additionalKbdVisible", newChecked)
                .apply()
            "auto_launch" -> Global.autoLaunch = if (newChecked) code else ""
            "auto_launch_gui" -> Global.autoLaunchGui = newChecked
            "reset_bootstrap" -> Global.shouldResetBootstrap = newChecked
            "use_legacy_proot" -> Global.useLegacyProot = newChecked
            "auto_check_update" -> Global.autoCheckUpdate = newChecked
        }
        refresh()
    }

    fun refresh() {
        val app = getApplication<Application>()
        val directTouch = x11Prefs.getString("touchMode", "1") == "2"
        val hideCutout = x11Prefs.getBoolean("hideCutout", true)
        val fullscreen = x11Prefs.getBoolean("fullscreen", true)
        val showEkBar = x11Prefs.getBoolean("showAdditionalKbd", false)

        _items.value = listOf(
            SwitchSetting(
                id = "x11_touch_direct",
                titleRes = R.string.tc4_settings_x11_touch_direct_title,
                descriptionRes = R.string.tc4_settings_x11_touch_direct_desc,
                isChecked = directTouch
            ),
            SwitchSetting(
                id = "x11_hide_cutout",
                titleRes = R.string.tc4_settings_x11_cutout_title,
                descriptionRes = R.string.tc4_settings_x11_cutout_desc,
                isChecked = hideCutout
            ),
            SwitchSetting(
                id = "x11_fullscreen",
                titleRes = R.string.tc4_settings_x11_fullscreen_title,
                descriptionRes = R.string.tc4_settings_x11_fullscreen_desc,
                isChecked = fullscreen
            ),
            SwitchSetting(
                id = "x11_ekbar",
                titleRes = R.string.tc4_settings_x11_ekbar_title,
                descriptionRes = R.string.tc4_settings_x11_ekbar_desc,
                isChecked = showEkBar
            ),
            ActionSetting(
                id = "x11_settings",
                titleRes = R.string.tc4_settings_x11_title,
                descriptionRes = R.string.tc4_settings_x11_desc
            ),
            ActionSetting(
                id = "ime_picker",
                titleRes = R.string.tc4_settings_ime_title,
                descriptionRes = R.string.tc4_settings_ime_desc
            ),
            ActionSetting(
                id = "launcher_shortcut",
                titleRes = R.string.tc4_settings_shortcut_title,
                descriptionRes = R.string.tc4_settings_shortcut_desc
            ),
            SwitchSetting(
                id = "auto_launch",
                titleRes = R.string.tc4_settings_auto_launch_title,
                descriptionRes = R.string.tc4_settings_auto_launch_desc,
                isChecked = code == Global.autoLaunch
            ),
            SwitchSetting(
                id = "auto_launch_gui",
                titleRes = R.string.tc4_settings_auto_launch_gui_title,
                descriptionRes = R.string.tc4_settings_auto_launch_desc,
                isChecked = Global.autoLaunchGui
            ),
            SwitchSetting(
                id = "use_legacy_proot",
                titleRes = R.string.tc4_settings_legacy_proot_title,
                descriptionRes = R.string.tc4_settings_legacy_proot_desc,
                isChecked = Global.useLegacyProot
            ),
            SwitchSetting(
                id = "reset_bootstrap",
                titleRes = R.string.tc4_settings_reset_bootstrap_title,
                descriptionRes = R.string.tc4_settings_reset_bootstrap_desc,
                isChecked = Global.shouldResetBootstrap
            ),
            SwitchSetting(
                id = "auto_check_update",
                titleRes = R.string.tc4_settings_auto_check_update_title,
                descriptionRes = R.string.tc4_settings_auto_check_update_desc,
                isChecked = Global.autoCheckUpdate
            ),
            ActionSetting(
                id = "manage_files",
                titleRes = R.string.tc4_settings_manage_files_title,
                descriptionRes = R.string.tc4_settings_manage_files_desc,
                isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            ),
            ActionSetting(
                id = "microphone",
                titleRes = R.string.tc4_settings_microphone_title,
                descriptionRes = R.string.tc4_settings_microphone_desc,
                isGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ),
            ActionSetting(
                id = "battery_unlimited",
                titleRes = R.string.tc4_settings_battery_title,
                descriptionRes = R.string.tc4_settings_battery_desc,
                isGranted = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
            )
        )
    }

    class Factory(
        private val application: Application,
        private val code: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(application, code) as T
        }
    }
}
