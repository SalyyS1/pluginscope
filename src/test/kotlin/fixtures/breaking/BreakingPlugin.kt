package fixtures.breaking

import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.LegacyOnly

/**
 * Touches, in order: a member the current API jar no longer has, a class it no longer has, a
 * member it deprecates, and a server-internal class. One fixture, four different verdicts.
 */
class BreakingPlugin {

    fun onEnable() {
        val server = Bukkit.getServer()
        println(server.motd)
        println(server.onlineMode)
        LegacyOnly().call()
        println(MinecraftServer().tickCount)
    }
}
