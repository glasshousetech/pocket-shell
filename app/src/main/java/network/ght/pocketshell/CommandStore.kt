package network.ght.pocketshell

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, atomic storage. A corrupt or newer library is never replaced by an empty save. */
object CommandStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "commands-v1.json"))

    @Synchronized fun load(context: Context): Result<List<SavedCommand>> = runCatching {
        val storage = file(context)
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return@runCatching emptyList()
        val root = JSONObject(storage.openRead().bufferedReader().use { it.readText() })
        require(root.getInt("version") == 1) { "This command library requires a newer PocketShell version." }
        val items = root.getJSONArray("commands")
        val seen = mutableSetOf<String>()
        List(items.length()) { i ->
            val row = items.getJSONObject(i)
            SavedCommand(row.getString("id"), row.getString("name"), row.getString("template")).also {
                CommandTemplate.validate(it)
                require(seen.add(it.id)) { "Duplicate command identity in the library." }
            }
        }
    }

    @Synchronized fun save(context: Context, command: SavedCommand): Result<List<SavedCommand>> = runCatching {
        CommandTemplate.validate(command)
        val current = load(context).getOrThrow().toMutableList()
        val index = current.indexOfFirst { it.id == command.id }
        if (index < 0) current.add(command) else current[index] = command
        write(context, current)
        DiagnosticLog.record(context, "commands", "saved")
        current
    }

    @Synchronized fun delete(context: Context, id: String): Result<List<SavedCommand>> = runCatching {
        val current = load(context).getOrThrow().filterNot { it.id == id }
        write(context, current)
        DiagnosticLog.record(context, "commands", "deleted")
        current
    }

    private fun write(context: Context, commands: List<SavedCommand>) {
        val rows = JSONArray()
        commands.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
        val bytes = JSONObject().put("version", 1).put("commands", rows).toString().toByteArray(Charsets.UTF_8)
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            storage.finishWrite(stream)
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
    }
}
