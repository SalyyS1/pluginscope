/*
 * A stand-in for a server-internal class. Nothing in the real Minecraft server is reproduced here;
 * the class exists only so a fixture plugin can reference the net.minecraft namespace and have the
 * internals check fire on it.
 */
package net.minecraft.server;

public final class MinecraftServer {

    public int getTickCount() {
        return 0;
    }
}
