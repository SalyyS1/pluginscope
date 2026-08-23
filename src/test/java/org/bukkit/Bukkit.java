/*
 * Minimal stand-ins for the server API, written for this project's tests.
 *
 * They exist so the fixture plugins can be compiled and then analysed as real bytecode without
 * pulling paper-api into the build. They are not Bukkit, Paper or Minecraft code, and they
 * implement nothing: only the shapes the analysers look at (static call sites, an interface with
 * a deprecated member, a class that one API jar has and another does not).
 */
package org.bukkit;

public final class Bukkit {

    private static Server server;

    private Bukkit() {
    }

    public static Server getServer() {
        return server;
    }
}
