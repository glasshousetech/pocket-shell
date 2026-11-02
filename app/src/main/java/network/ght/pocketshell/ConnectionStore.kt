package network.ght.pocketshell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedConnection(val id: String, val profile: SshProfile, val favorite: Boolean = false)

/** One atomic preference value; legacy single-server fields are preserved during migration. */
object ConnectionStore {
    private const val KEY = "library_v1"
    private fun preferences(context: Context) = context.getSharedPreferences("ssh_profiles", Context.MODE_PRIVATE)

    @Synchronized fun load(context: Context): Result<List<SavedConnection>> = runCatching {
        val raw = preferences(context).getString(KEY, null)
        if (raw == null) {
            val legacy = SshProfiles.custom(context)
            val migrated = if (legacy.host.isBlank()) emptyList() else listOf(SavedConnection(UUID.randomUUID().toString(), legacy))
            write(context, migrated)
            migrated
        } else {
            val values = JSONArray(raw)
            val seen = mutableSetOf<String>()
            List(values.length()) { index ->
                val item = values.getJSONObject(index)
                val id = item.getString("id")
                check(seen.add(id)) { "Duplicate saved connection identity." }
                SavedConnection(id, SshProfile(item.getString("name"), item.getString("host"),
                    item.optString("user"), item.getInt("port"), item.optString("tmux")), item.optBoolean("favorite"))
            }
        }
    }

    @Synchronized fun save(context: Context, connection: SavedConnection): Result<List<SavedConnection>> = runCatching {
        require(connection.profile.name.isNotBlank()) { "Enter a connection name." }
        require(connection.profile.name.length <= 80 && connection.profile.host.length <= 253) { "Connection name or host is too long." }
        connection.profile.command() // Validate without opening a connection.
        val current = load(context).getOrThrow().toMutableList()
        val index = current.indexOfFirst { it.id == connection.id }
        if (index < 0) { require(current.size < 100) { "The library supports up to 100 saved connections." }; current.add(connection) }
        else current[index] = connection
        write(context, current)
        DiagnosticLog.record(context, "connections", "profile_saved")
        current
    }

    @Synchronized fun delete(context: Context, id: String): Result<List<SavedConnection>> = runCatching {
        val remaining = load(context).getOrThrow().filterNot { it.id == id }
        write(context, remaining)
        DiagnosticLog.record(context, "connections", "profile_removed")
        remaining
    }

    private fun write(context: Context, connections: List<SavedConnection>) {
        val entries = JSONArray()
        connections.forEach { saved ->
            val p = saved.profile
            entries.put(JSONObject().put("id", saved.id).put("name", p.name).put("host", p.host)
                .put("user", p.user).put("port", p.port).put("tmux", p.tmuxSession).put("favorite", saved.favorite))
        }
        check(preferences(context).edit().putString(KEY, entries.toString()).commit()) { "The connection library could not be saved. Retry before leaving this screen." }
    }
}
