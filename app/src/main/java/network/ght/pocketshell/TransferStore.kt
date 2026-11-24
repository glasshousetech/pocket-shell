package network.ght.pocketshell

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Private receipts contain paths and operation phases, never keys or file contents. */
object TransferStore {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "file-transfers-v1.json"))
    private fun encode(path: SftpClient.Path) = Base64.getEncoder().encodeToString(path.bytes)
    private fun decode(value: String) = SftpClient.Path.of(Base64.getDecoder().decode(value))

    @Synchronized fun load(context: Context): List<FileTransfer> {
        val storage = file(context)
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(storage.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("version") == 1) { "These transfer receipts require a newer app. Existing receipts were kept." }
        val rows = root.getJSONArray("transfers")
        val seen = mutableSetOf<String>()
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val p = row.getJSONObject("profile")
            FileTransfer(row.getString("id"),
                SshProfile(p.getString("name"), p.getString("host"), p.getString("user"), p.getInt("port"), p.getString("tmux"), p.getString("remote")),
                decode(row.getString("target")), decode(row.getString("staged")), decode(row.getString("backup")),
                row.getString("sha256"), row.getLong("size"), FileTransfer.Phase.valueOf(row.getString("phase")), row.getString("message"))
                .validate().also { require(seen.add(it.id)) }
        }
    }
    @Synchronized fun save(context: Context, item: FileTransfer) {
        item.validate()
        val rows = load(context).toMutableList()
        val index = rows.indexOfFirst { it.id == item.id }
        if (index < 0) rows.add(0, item) else rows[index] = item
        val data = JSONArray()
        rows.forEach { t ->
            val p = t.profile
            data.put(JSONObject().put("id", t.id).put("target", encode(t.target)).put("staged", encode(t.staged))
                .put("backup", encode(t.backup)).put("sha256", t.sha256).put("size", t.size)
                .put("phase", t.phase.name).put("message", t.message)
                .put("profile", JSONObject().put("name", p.name).put("host", p.host).put("user", p.user)
                    .put("port", p.port).put("tmux", p.tmuxSession).put("remote", p.remoteCommand)))
        }
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("transfers", data).toString().toByteArray())
            storage.finishWrite(stream)
        } catch (error: Exception) { storage.failWrite(stream); throw error }
    }
}
