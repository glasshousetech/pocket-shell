package network.ght.pocketshell

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Best-effort persistence for the tab structure across a full process kill
 * (low-memory-killer, force-stop, reboot). [TermService]'s foreground service
 * already keeps sessions alive across backgrounding/Activity recreation — this
 * covers the case that survives *nothing*: the actual PTY/shell processes are
 * gone. Save the retained output as explicitly historical text. An exact remote
 * workspace identity can additionally reconnect to a still-running server-side
 * process; the local snapshot itself never establishes that the process survived.
 *
 * Uses org.json (bundled with the Android SDK) — no extra dependency needed.
 * Lives in [Context.getNoBackupFilesDir] rather than filesDir: it's internal
 * state, not something that belongs in the shell's $HOME, and scrollback can
 * contain sensitive command output that shouldn't ride along in cloud backups.
 */
object SessionStore {
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PocketShell-session-store").apply { isDaemon = true }
    }

    private const val FILE_NAME = "sessions_snapshot.json"
    private const val VERSION = 3

    // Writer-thread state. Identical periodic snapshots are not rewritten, and an existing
    // file's version is verified once per process instead of re-parsing it before every save.
    private var lastWritten: List<Saved>? = null
    private var existingVersionVerified = false

    data class Saved(
        val mode: SessionMode,
        val title: String,
        val cwd: String?,
        val transcript: String,
        val tabId: String = TabIdentity.create(),
        val customName: String? = null,
        val color: String? = null,
        val startupCommand: String? = null,
        val connectionProfile: SshProfile? = null,
        val remoteWorkspace: RemoteWorkspace? = null,
        val restoreNotice: String? = null,
    )

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** Overwrites the snapshot. Pass an empty list to effectively clear it. */
    fun save(context: Context, sessions: List<Saved>) {
        val appContext = context.applicationContext
        val snapshot = sessions.toList()
        writer.execute { saveOnWriter(appContext, snapshot) }
    }

    private fun saveOnWriter(context: Context, sessions: List<Saved>) {
        if (sessions.isEmpty()) {
            clearOnWriter(context)
            return
        }
        if (sessions == lastWritten && file(context).exists()) return
        runCatching {
            if (!existingVersionVerified && file(context).exists()) {
                val previous = JSONObject(AtomicFile(file(context)).openRead().bufferedReader().use { it.readText() })
                require(previous.optInt("version", 1) in 1..VERSION) { "Unsupported snapshot version; existing history was kept." }
            }
            existingVersionVerified = true
            val arr = JSONArray()
            sessions.forEach { s ->
                val o = JSONObject()
                o.put("mode", s.mode.name)
                o.put("tabId", TabIdentity.requireValid(s.tabId))
                s.customName?.let { o.put("customName",it) }
                s.color?.let { o.put("color",it) }
                s.startupCommand?.let { o.put("startupCommand",it) }
                s.connectionProfile?.let { p -> o.put("connection", JSONObject().put("name",p.name).put("host",p.host)
                    .put("user",p.user).put("port",p.port).put("tmux",p.tmuxSession).put("remote",p.remoteCommand)) }
                s.remoteWorkspace?.validate()?.let { w -> o.put("remoteWorkspace", JSONObject()
                    .put("id", w.id).put("created", w.created).put("name", w.name)
                    .put("windows", w.windows).put("clients", w.clients)
                    .put("serverPid", w.serverPid).put("serverStarted", w.serverStarted)) }
                o.put("title", s.title)
                if (!s.cwd.isNullOrBlank()) o.put("cwd", s.cwd)
                o.put("transcript", s.transcript)
                arr.put(o)
            }
            val root = JSONObject().put("version", VERSION).put("sessions", arr)

            val atomic = AtomicFile(file(context))
            val stream = atomic.startWrite()
            try {
                stream.write(root.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
                lastWritten = sessions
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        }.onFailure {
            Log.w("PocketShell.SessionStore", "Could not save session history (${it.javaClass.simpleName})")
            DiagnosticLog.record(context, "session_store", "save_failed", it.javaClass.simpleName)
        }
    }

    /** Reads the snapshot, if any. Never throws — a corrupt file just yields nothing. */
    fun load(context: Context): List<Saved> {
        // Cold service startup only. Serialize with earlier saves and deliberate clears.
        return writer.submit<List<Saved>> { loadOnWriter(context.applicationContext) }.get()
    }

    private fun loadOnWriter(context: Context): List<Saved> {
        val f = file(context)
        return runCatching {
            val root = JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
            require(root.optInt("version", 1) in 1..VERSION) { "Unsupported snapshot version" }
            val arr = root.optJSONArray("sessions") ?: return emptyList()
            val seen = mutableSetOf<String>()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mode = runCatching { SessionMode.valueOf(o.optString("mode")) }.getOrNull()
                    ?: return@mapNotNull null
                val restoredId = TabIdentity.restore(o.optString("tabId")).let { id ->
                    if (seen.add(id)) id else TabIdentity.create().also { seen.add(it) }
                }
                val profile = o.optJSONObject("connection")?.let { p -> runCatching {
                    SshProfile(p.getString("name"), p.getString("host"), p.optString("user"), p.getInt("port"), p.optString("tmux"), p.optString("remote"))
                        .also { it.command() }
                }.getOrNull() }
                val workspace = o.optJSONObject("remoteWorkspace")?.let { w -> runCatching {
                    RemoteWorkspace(w.getString("id"), w.getLong("created"), w.getString("name"),
                        w.getInt("windows"), w.getInt("clients"), w.getLong("serverPid"), w.getLong("serverStarted")).validate()
                        .also { require(mode == SessionMode.LINUX && profile != null); RemoteWorkspaces.attachCommand(requireNotNull(profile), it) }
                }.getOrNull() }
                val rejectedWorkspace = o.has("remoteWorkspace") && workspace == null
                if (rejectedWorkspace) DiagnosticLog.record(context, "remote_workspace", "restore_identity_rejected", "InvalidSavedIdentity", restoredId)
                Saved(
                    mode = mode,
                    title = o.optString("title").ifBlank { "sh" },
                    cwd = o.optString("cwd").takeIf { it.isNotBlank() },
                    transcript = o.optString("transcript"),
                    customName = TabNames.normalize(o.optString("customName")),
                    color = TabColors.byId(o.optString("color"))?.id,
                    // Legacy commands without an explicit tab identity could rejoin a
                    // shared tmux workspace. Keep their history, require a fresh Connect.
                    startupCommand = o.optString("startupCommand").takeIf { !rejectedWorkspace && it.contains(restoredId) },
                    connectionProfile = if (rejectedWorkspace) null else profile,
                    remoteWorkspace = workspace,
                    restoreNotice = if (rejectedWorkspace) "Saved remote identity could not be verified. History was kept; use Remote workspaces to reconnect." else null,
                    tabId = restoredId,
                )
            }
        }.getOrElse { failure ->
            if (f.exists()) DiagnosticLog.record(context, "session_store", "read_failed", failure.javaClass.simpleName)
            emptyList()
        }
    }

    /** Consumes the snapshot so a later clean cold start doesn't replay stale history. */
    fun clear(context: Context) {
        val appContext = context.applicationContext
        writer.execute { clearOnWriter(appContext) }
    }

    private fun clearOnWriter(context: Context) {
        lastWritten = null
        AtomicFile(file(context)).delete()
        runCatching { File(file(context).parentFile, "$FILE_NAME.tmp").delete() }
    }
}
