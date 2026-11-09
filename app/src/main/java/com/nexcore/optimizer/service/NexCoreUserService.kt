package com.nexcore.optimizer.service

import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * Shizuku UserService — runs in a separate process with shell UID (uid=2000).
 *
 * Shizuku starts this service via its privileged helper and exposes it to the
 * main app process over an AIDL binder. All methods here execute with the
 * permissions of the shell user, which allows:
 *   - Writing to /sys paths the app cannot reach
 *   - Running `wm density`, `settings put secure`, `ndc`, etc.
 *   - Reading /proc files restricted to uid≥0 but not to app sandbox
 *
 * Keep this class minimal — heavy logic lives in the core/ layer of the app process.
 */
class NexCoreUserService : IUserService.Stub() {

    companion object {
        private const val TAG = "NexCoreService"
    }

    override fun executeCommand(cmd: String): String {
        Log.d(TAG, "exec: $cmd")
        return try {
            val p   = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (err.isNotBlank()) Log.w(TAG, "stderr: $err")
            (out + err).trim()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    override fun readFile(path: String): String {
        return try {
            File(path).readText()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun writeFile(path: String, content: String): Boolean {
        return try {
            File(path).writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeFile $path: ${e.message}")
            false
        }
    }

    override fun destroy() {
        Log.d(TAG, "Service destroyed")
    }

    // Required by Shizuku — return this binder from the service's onBind
    fun asBinder(): IBinder = this
}