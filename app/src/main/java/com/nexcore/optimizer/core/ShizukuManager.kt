package com.nexcore.optimizer.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.nexcore.optimizer.service.IUserService
import com.nexcore.optimizer.service.NexCoreUserService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Manages Shizuku binder lifecycle, permission, and UserService binding.
 *
 * Usage:
 *   val shizuku = ShizukuManager(context)
 *   shizuku.init()                       // call from Application.onCreate
 *   shizuku.shell("wm density 360")      // run as shell uid=2000
 *   shizuku.putSetting("system","pointer_speed","3")
 */
class ShizukuManager(private val context: Context) {

    companion object { const val REQ_PERM = 1001 }

    private var svc: IUserService? = null
    private val _ok = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _ok.asStateFlow()

    private val onBound = Shizuku.OnBinderReceivedListener {
        Blackbox.log("SHIZUKU", "Binder received · uid=${Shizuku.getUid()}")
        handleBinder()
    }
    private val onDead = Shizuku.OnBinderDeadListener {
        _ok.value = false; svc = null
        Blackbox.warn("SHIZUKU", "Binder dead — waiting for restart")
    }
    private val onPerm = Shizuku.OnRequestPermissionResultListener { _, r ->
        if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) bindUserService()
        else Blackbox.warn("SHIZUKU", "Permission denied by user")
    }

    fun init() {
        Shizuku.addBinderReceivedListener(onBound)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPerm)
        if (Shizuku.pingBinder()) handleBinder()
    }

    private fun handleBinder() {
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            bindUserService()
        else
            Shizuku.requestPermission(REQ_PERM)
    }

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, NexCoreUserService::class.java.name)
    ).daemon(false).processNameSuffix("service").debuggable(false).version(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = IUserService.Stub.asInterface(b)
            _ok.value = true
            Blackbox.log("SHIZUKU", "UserService connected")
        }
        override fun onServiceDisconnected(n: ComponentName?) {
            svc = null; _ok.value = false
            Blackbox.warn("SHIZUKU", "UserService disconnected")
        }
    }

    private fun bindUserService() = Shizuku.bindUserService(args, conn)

    /**
     * Run [cmd] as shell (uid=2000) via Shizuku UserService.
     * Returns stdout; empty string on error.
     */
    fun shell(cmd: String): String = try {
        svc?.executeCommand(cmd) ?: localShell(cmd)
    } catch (e: RemoteException) {
        Blackbox.err("SHIZUKU", "RemoteException: ${e.message}")
        ""
    }

    private fun localShell(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        p.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }

    /** `settings put <namespace> <key> <value>` */
    fun putSetting(ns: String, key: String, value: String): Boolean {
        val out = shell("settings put $ns $key $value")
        Blackbox.log("SETTINGS", "$ns/$key=$value")
        return out.isBlank() || !out.contains("Error", ignoreCase = true)
    }

    fun isAvailable() = _ok.value && try { Shizuku.pingBinder() } catch (_: Exception) { false }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(onBound)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPerm)
        if (_ok.value) Shizuku.unbindUserService(args, conn, true)
        _ok.value = false
    }
}