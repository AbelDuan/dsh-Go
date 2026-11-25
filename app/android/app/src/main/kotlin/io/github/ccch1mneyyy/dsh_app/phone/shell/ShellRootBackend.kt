package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.phone.background.Bundles
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root shell channel. `su -c` starts the very same [AndroidShellMain] the self-ADB backend talks
 * to, so job handling, output limits and cancellation are the ones already in use; the only new
 * piece is the transport, which is this process's own pipes.
 *
 * Nothing outside the APK is needed: the superuser manager authorizes `su` once for the App and
 * that authorization *is* the capability — no KernelSU module, no daemon to install, no pairing.
 */
internal class ShellRootBackend(private val context: Context, private val onChanged: () -> Unit = {}) {
    private val guard = Any()
    private var link: ShellLink? = null
    private var lastError: String? = null

    fun status(): Map<String, Any?> = synchronized(guard) {
        linkedMapOf("available" to (link?.alive() == true), "lastError" to lastError)
    }

    fun connect(wanted: () -> Boolean): ShellConnection = synchronized(guard) {
        link?.takeIf { it.alive() }?.let { return ShellConnection(it) }
        closeLocked()
        if (!wanted()) return ShellConnection(errorCode = "cancelled")
        val child = try {
            ProcessBuilder(command()).start()
        } catch (_: Exception) {
            lastError = "rootUnavailable"
            onChanged()
            return ShellConnection(errorCode = "rootUnavailable")
        }
        // su and the manager write here too (authorization prompts, denials): drain, keep nothing.
        Thread({
            runCatching {
                val buffer = ByteArray(4096)
                while (child.errorStream.read(buffer) >= 0) { }
            }
        }, "dsh-shell-root-stderr").apply { isDaemon = true; start() }
        if (!wanted()) {
            runCatching { child.destroyForcibly() }
            return ShellConnection(errorCode = "cancelled")
        }
        val input = child.inputStream
        val output = child.outputStream
        val synced = AtomicBoolean(false)
        val created = ShellLink("root", { request ->
            if (!synced.getAndSet(true)) ShellWire.sync(input)
            ShellWire.write(output, Bundles.marshal(request))
            Bundles.unmarshal(ShellWire.read(input) ?: throw IOException("transportLost"))
        }, { child.isAlive }, { runCatching { child.destroyForcibly() } })
        link = created
        lastError = null
        onChanged()
        ShellConnection(created)
    }

    fun disconnect() = synchronized(guard) {
        closeLocked()
        onChanged()
    }

    private fun closeLocked() {
        val current = link
        link = null
        current?.close()
    }

    /** The command `su` runs: the APK's own classes, the shell host, on the child's stdio. */
    private fun command(): List<String> {
        val info = context.applicationInfo
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        require(paths.all { it.startsWith('/') && ':' !in it && '\u0000' !in it })
        val classpath = paths.joinToString(":") { "'" + it.replace("'", "'\\''") + "'" }
        return listOf(
            "su", "-c",
            "CLASSPATH=$classpath exec /system/bin/app_process /system/bin ${AndroidShellMain::class.java.name}",
        )
    }
}
