package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a root command relay alive for the Linux environment.
 *
 * The agent's shell in the guest (`~/.dsh/root-relay/rsh`) connects to 127.0.0.1:37121 and runs
 * its commands as uid 0. The relay binary ships inside the APK, so the only capability it needs
 * is the same superuser grant the App already holds for its own root shell channel — there is no
 * module to install and no daemon to keep alive by hand.
 *
 * It runs once per App process, and is a no-op when the relay is already listening and the guest
 * holds the matching token.
 */
internal object RootRelay {
    private const val TAG = "dsh-root-relay"
    private const val DIR = "/data/local/tmp/dsh-root"
    private const val PORT = 37121
    private const val ASSET = "rootrelay/dsr"
    private const val PREFS = "dsh_root_relay"
    private const val TOKEN = "token"
    private val attempted = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (!attempted.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            runCatching { start(app) }
                .onFailure { Log.w(TAG, "root relay unavailable: ${it.message}") }
                .onSuccess { if (it) Log.i(TAG, "root relay listening on 127.0.0.1:$PORT") }
        }, "dsh-root-relay").apply { isDaemon = true }.start()
    }

    private fun start(context: Context): Boolean {
        // The guest keeps its client (rsh / relay.py) and the token next to each other; when the
        // runtime was reinstalled the token is gone there and has to be written again.
        val guest = File(context.filesDir, "runtime/rootfs/root/.dsh/root-relay")
        val guestToken = File(guest, TOKEN)
        if (listening() && guestToken.isFile()) return true

        val binary = materialise(context)
        val token = token(context)
        val script = buildString {
            append("R=").append(DIR).append('\n')
            append("mkdir -p $R; chmod 700 $R\n")
            append("cp '").append(binary.absolutePath).append("' $R/dsr\n")
            append("chmod 700 $R/dsr\n")
            append("printf '%s\\n' ").append(token).append(" > $R/token\n")
            append("chmod 600 $R/token\n")
            if (guest.isDirectory || guest.mkdirs()) {
                append("printf '%s\\n' ").append(token).append(" > '").append(guestToken.absolutePath).append("'\n")
                append("chmod 600 '").append(guestToken.absolutePath).append("'\n")
                append("printf '%s\\n' ").append(PORT).append(" > '").append(File(guest, "port").absolutePath).append("'\n")
            }
            append("pgrep -f \"$R/dsr\" >/dev/null 2>&1 || ( setsid nohup $R/dsr >/dev/null 2>&1 & )\n")
        }
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        Thread({ runCatching { process.inputStream.use { it.readBytes() } } }, "dsh-root-relay-log").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            runCatching { process.destroyForcibly() }
            Log.w(TAG, "su did not answer within 15 s; the relay was not started")
            return false
        }
        return listening()
    }

    private fun materialise(context: Context): File {
        val target = File(context.filesDir, "dsr")
        val declared = runCatching {
            context.assets.openFd(ASSET).use { it.declaredLength }
        }.getOrNull()
        if (target.isFile && (declared == null || target.length() == declared)) return target
        context.assets.open(ASSET).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN, null)?.takeIf { it.length >= 16 }?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(TOKEN, fresh).apply()
        return fresh
    }

    private fun listening(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300) }
        true
    }.getOrDefault(false)
}
