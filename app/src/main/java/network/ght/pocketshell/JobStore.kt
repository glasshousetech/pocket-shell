package network.ght.pocketshell

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object JobStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "remote-jobs-v1.json"))
    @Synchronized fun load(context: Context): List<RemoteJob> {
        val storage = file(context)
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(storage.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("version") == 1) { "These job records require a newer PocketShell version." }
        val rows = root.getJSONArray("jobs")
        val seen = mutableSetOf<String>()
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val p = row.getJSONObject("profile")
            val o = row.optJSONObject("observation")
            RemoteJob(row.getString("id"), row.getString("name"),
                SshProfile(p.getString("name"), p.getString("host"), p.getString("user"), p.getInt("port"), p.getString("tmux"), p.getString("remote")),
                row.getString("command"), row.getString("directory"), row.getLong("created"),
                o?.let { JobObservation(it.getString("state"), it.getLong("started"), it.getLong("finished"),
                    if (it.isNull("exit")) null else it.getInt("exit"), it.getLong("checked"), it.getString("remoteDirectory")) },
                if (row.isNull("connectionError")) null else row.getString("connectionError"),
                if (row.isNull("notifiedResult")) null else row.getString("notifiedResult"),
                row.optBoolean("watching", true))
                .validate().also { require(seen.add(it.id)) }
        }
    }
    @Synchronized fun create(context: Context, job: RemoteJob): List<RemoteJob> {
        job.validate()
        val existing = load(context)
        val previous = existing.firstOrNull { it.id == job.id } ?: return writeJob(context, job)
        require(previous.profile == job.profile && previous.command == job.command && previous.directory == job.directory && previous.created == job.created) { "A job identity cannot be reused for different work." }
        return existing
    }
    @Synchronized fun update(context: Context, id: String, change: (RemoteJob) -> RemoteJob): List<RemoteJob> {
        val previous = load(context).first { it.id == id }
        val changed = change(previous)
        require(changed.id == id)
        return writeJob(context, changed)
    }
    private fun writeJob(context: Context, job: RemoteJob): List<RemoteJob> {
        job.validate()
        val jobs = load(context).toMutableList()
        val index = jobs.indexOfFirst { it.id == job.id }
        if (index < 0) jobs.add(0, job) else {
            val original = jobs[index]
            require(original.profile == job.profile && original.command == job.command && original.directory == job.directory && original.created == job.created) { "A job identity cannot be reused for a different command or destination." }
            jobs[index] = job
        }
        val rows = JSONArray()
        jobs.forEach { item ->
            val p = item.profile
            val row = JSONObject().put("id", item.id).put("name", item.name).put("command", item.command)
                .put("directory", item.directory).put("created", item.created).put("watching", item.watching)
                .put("connectionError", item.connectionError ?: JSONObject.NULL)
                .put("notifiedResult", item.notifiedResult ?: JSONObject.NULL)
                .put("profile", JSONObject().put("name", p.name).put("host", p.host).put("user", p.user)
                    .put("port", p.port).put("tmux", p.tmuxSession).put("remote", p.remoteCommand))
            item.observation?.let { o ->
                row.put("observation", JSONObject().put("state", o.state).put("started", o.started).put("finished", o.finished)
                    .put("exit", o.exit ?: JSONObject.NULL).put("checked", o.checked).put("remoteDirectory", o.remoteDirectory))
            }
            rows.put(row)
        }
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("jobs", rows).toString().toByteArray())
            storage.finishWrite(stream)
        } catch (failure: Exception) { storage.failWrite(stream); throw failure }
        return jobs
    }
}
