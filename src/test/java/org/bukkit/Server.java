package org.bukkit;

public interface Server {

    String getName();

    /** Removed from the "current" API jar by the fixture builder, to exercise the member check. */
    String getMotd();

    @Deprecated
    boolean getOnlineMode();
}
