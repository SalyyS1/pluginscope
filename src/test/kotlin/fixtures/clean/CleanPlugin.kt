package fixtures.clean

import org.bukkit.Bukkit

/**
 * A plugin that only touches API which exists in every fixture API jar. Used as the
 * false-positive guard: auditing it must stay quiet.
 */
class CleanPlugin {

    fun onEnable() {
        val server = Bukkit.getServer()
        println(server.name)
    }
}
