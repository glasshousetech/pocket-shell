package network.ght.pocketshell

import java.util.UUID

/** Carried forward from the October 31 isolation candidate. Never a recycled display index. */
object TabIdentity {
    private val valid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    fun create(): String = UUID.randomUUID().toString()
    fun requireValid(id: String): String {
        require(valid.matches(id)) { "Invalid terminal tab identity." }
        return id
    }
    fun restore(id: String): String = id.takeIf { valid.matches(it) } ?: create()
}
