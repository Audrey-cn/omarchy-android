// MainActivity.kt -- This file is part of tiny_container.
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

package com.fct.tc4.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4ActivityMainBinding
import com.fct.tc4.ui.misc.WipeLayout
import com.fct.tc4.ui.page.ContainerInstallFragment
import com.fct.tc4.ui.page.ContainerMainFragment
import com.fct.tc4.ui.page.ContainerManageFragment
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.misc.LauncherShortcutStore
import com.fct.tc4.ui.misc.shouldReturnToX11
import com.fct.tc4.ui.page.TerminalFragment
import com.fct.tc4.ui.page.ContainerManageViewModel
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: Tc4ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var pendingImportCount = 0
    private var pendingShortcutNavigation = false
    private var desktopForwardScheduled = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        binding = Tc4ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Phone management UI stays in crisp Portrait (tablets sw>=600dp remain free; X11 desktop is independent)
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setSupportActionBar(binding.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            // 软键盘弹出时，将 WipeLayout（终端容器）上推
            // 需减去导航栏/手势横条高度——imeInsets 可能已包含该区域，避免抬得过多
            val keyboardHeight = (imeInsets.bottom - navInsets.bottom).coerceAtLeast(0)
            binding.wiper.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = keyboardHeight
            }
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screen.collect { screen ->
                        showFragmentForScreen(screen)
                        invalidateOptionsMenu()
                    }
                }
                launch {
                    viewModel.isTerminalOpen.collect { open ->
                        if (open) {
                            showTerminal()
                        } else {
                            hideTerminal()
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null && !returnToLiveDesktop()) {
            resolveIntentAction(intent)
        }

        // 拦截返回键：终端打开时走动画退出，不直接弹出后栈
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.isTerminalOpen.value) {
                viewModel.closeTerminal()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    private fun showTerminal() {
        val existing = supportFragmentManager.findFragmentById(R.id.terminal_overlay)
        if (existing != null && existing.view != null) {
            binding.root.post {
                (existing as? TerminalFragment)?.getBlurView()?.setupWith(binding.blurTarget)
                    ?.setBlurRadius(2f)
            }
            return
        }
        val fragment = TerminalFragment()
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            addToBackStack("terminal")
            replace(R.id.terminal_overlay, fragment, "Terminal")
        }
        binding.root.post {
            val addedFragment = supportFragmentManager.findFragmentByTag("Terminal") as? TerminalFragment
            addedFragment?.let {
                it.getBlurView()?.setupWith(binding.blurTarget)?.setBlurRadius(2f)
                binding.wiper.startWipeAnimation(
                    isIn = true,
                    direction = WipeLayout.Direction.TOP_TO_BOTTOM,
                    durationMs = 300
                )
            }
        }
    }

    private fun hideTerminal() {
        val fragment = supportFragmentManager.findFragmentByTag("Terminal")
        if (fragment != null) {
            binding.wiper.startWipeAnimation(
                isIn = false,
                direction = WipeLayout.Direction.BOTTOM_TO_TOP,
                durationMs = 300
            ) {
                supportFragmentManager.popBackStack()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.terminalToggle -> {
                viewModel.toggleTerminal()
                true
            }
            R.id.containerImport -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerManage") as? ContainerManageFragment
                fragment?.onImportAction()
                true
            }
            R.id.enterGui -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerMain") as? ContainerMainFragment
                fragment?.onEnterGui()
                true
            }
            R.id.file -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerInstall") as? ContainerInstallFragment
                fragment?.onFileAccessAction()
                true
            }
            R.id.battery -> {
                val fragment = supportFragmentManager.findFragmentByTag("ContainerInstall") as? ContainerInstallFragment
                fragment?.onBatteryAction()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        returnToLiveDesktop()
    }

    override fun onPause() {
        super.onPause()
        desktopForwardScheduled = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!returnToLiveDesktop(intent)) resolveIntentAction(intent)
    }

    private fun returnToLiveDesktop(launchIntent: Intent? = intent): Boolean {
        val action = launchIntent?.action
        val externalEntry = launchIntent?.data != null ||
            (action != null && action != Intent.ACTION_MAIN)
        val pendingAction = externalEntry || pendingImportCount > 0 || pendingShortcutNavigation ||
            MainViewModel.pendingCommandText != null
        if (!shouldReturnToX11(Global.x11SessionActive, Global.userRequestedManagementPage, pendingAction)) return false
        if (desktopForwardScheduled) return true
        return try {
            desktopForwardScheduled = true
            startActivity(Intent(this, com.termux.x11.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            })
            true
        } catch (_: RuntimeException) {
            desktopForwardScheduled = false
            false
        }
    }

    private fun consumeExternalIntent() {
        // Recents must not keep treating an already handled share/shortcut as pending.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    private fun showFragmentForScreen(screen: MainViewModel.Screen) {
        val fragment: Fragment
        val tag: String
        when (screen) {
            is MainViewModel.Screen.Init -> return
            is MainViewModel.Screen.ContainerManage -> {
                fragment = ContainerManageFragment()
                tag = "ContainerManage"
            }
            is MainViewModel.Screen.ContainerInstall -> {
                fragment = ContainerInstallFragment().apply {
                    arguments = Bundle().apply {
                        putString("code", screen.code)
                        putString("webpage", screen.webpage)
                    }
                }
                tag = "ContainerInstall"
            }
            is MainViewModel.Screen.ContainerMain -> {
                fragment = ContainerMainFragment().apply {
                    arguments = Bundle().apply {
                        putString("code", screen.code)
                    }
                }
                tag = "ContainerMain"
            }
        }
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment, tag)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return when (viewModel.screen.value) {
            is MainViewModel.Screen.ContainerManage -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_manage, menu)
                true
            }
            is MainViewModel.Screen.ContainerMain -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_main, menu)
                true
            }
            is MainViewModel.Screen.ContainerInstall -> {
                menuInflater.inflate(R.menu.tc4_fragment_container_install, menu)
                true
            }
            else -> {
                // Init 无菜单
                false
            }
        }
    }

    // ─── Intent 解析 ──────────────────────────────────────────

    private fun resolveIntentAction(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MAIN -> onNormalLaunch()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                onImportFile(intent)
                consumeExternalIntent()
            }
            ACTION_SHORTCUT -> {
                onShortcutLaunch(intent)
                consumeExternalIntent()
            }
            else -> onNormalLaunch()
        }
    }

    private fun onNormalLaunch() {
        // 已完成初始导航，不再重复导航（singleTask 下 onNewIntent 可能再次触发 ACTION_MAIN）
        if (viewModel.screen.value !is MainViewModel.Screen.Init) return

        // A recreated management Activity must never start another container over the
        // running desktop. An in-flight import is forwarded by its completion handler.
        if (Global.x11SessionActive) {
            if (Global.userRequestedManagementPage) viewModel.navigateTo(MainViewModel.Screen.ContainerManage)
            else returnToLiveDesktop()
            return
        }

        if (Global.installedContainers.isEmpty()) {
            startActivity(Intent(this, com.fct.tc4.ui.page.DesktopSetupActivity::class.java))
            finish()
            return
        }

        val autoCode = Global.autoLaunch
        if (autoCode.isNotEmpty() && autoCode in Global.installedContainers) {
            viewModel.navigateTo(MainViewModel.Screen.ContainerMain(autoCode))
        } else {
            if (autoCode.isNotEmpty()) {
                Global.autoLaunch = ""
            }
            viewModel.navigateTo(MainViewModel.Screen.ContainerManage)
        }
    }

    private fun onImportFile(intent: Intent) {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }

        if (uris.isEmpty()) {
            if (viewModel.screen.value is MainViewModel.Screen.Init) {
                onNormalLaunch()
            }
            return
        }

        pendingImportCount++
        lifecycleScope.launch {
            try {
                val copiedCount = withContext(Dispatchers.IO) {
                    val app = application
                    val publicDir = File(app.filesDir, "public")
                    publicDir.mkdirs()

                    var copied = 0
                    uris.forEach { uri ->
                        try {
                            val fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
                            app.contentResolver.openInputStream(uri)?.use { input ->
                                File(publicDir, fileName).outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 8192)
                                }
                            }
                            copied++
                        } catch (_: Exception) { }
                    }
                    copied
                }
                Snackbar.make(binding.root,
                    getString(R.string.tc4_import_file_result, copiedCount, uris.size),
                    Snackbar.LENGTH_SHORT).show()
                if (viewModel.screen.value is MainViewModel.Screen.Init) onNormalLaunch()
            } finally {
                pendingImportCount--
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) returnToLiveDesktop()
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        if (name == null) name = uri.lastPathSegment
        return name?.substringAfterLast('/')?.substringAfterLast('\\')
    }

    private fun onShortcutLaunch(intent: Intent) {
        val shortcut = LauncherShortcutStore.resolve(this, intent.getStringExtra(LauncherShortcutStore.EXTRA_ID))
        if (shortcut == null) {
            Snackbar.make(binding.root, R.string.tc4_shortcut_invalid, Snackbar.LENGTH_LONG).show()
            if (viewModel.screen.value is MainViewModel.Screen.Init) onNormalLaunch()
            return
        }
        val code = shortcut.code
        val command = shortcut.command

        // 检查容器是否存在
        if (code !in Global.installedContainers) {
            Snackbar.make(binding.root, getString(R.string.tc4_shortcut_container_not_found, code), Snackbar.LENGTH_LONG).show()
            if (viewModel.screen.value is MainViewModel.Screen.Init) {
                onNormalLaunch()
            }
            return
        }

        val screen = viewModel.screen.value

        when (screen) {
            is MainViewModel.Screen.Init,
            is MainViewModel.Screen.ContainerManage -> {
                MainViewModel.pendingCommandCode = code
                MainViewModel.pendingCommandText = command
                viewModel.navigateTo(MainViewModel.Screen.ContainerMain(code))
            }
            is MainViewModel.Screen.ContainerMain -> {
                if (screen.code == code) {
                    Global.sendCommand(command)
                    pendingShortcutNavigation = true
                    lifecycleScope.launch {
                        try {
                            delay(32)
                            (supportFragmentManager.findFragmentByTag("ContainerMain") as? ContainerMainFragment)?.onEnterGui()
                        } finally {
                            pendingShortcutNavigation = false
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) returnToLiveDesktop()
                        }
                    }
                } else {
                    Snackbar.make(binding.root, R.string.tc4_shortcut_another_running, Snackbar.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    companion object {
        const val ACTION_SHORTCUT = "com.fct.tc4.action.SHORTCUT"
    }
}
