package dev.astera.loader;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Loader implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("loader");

    @Override
    public void onPreLaunch() {
        try {
            RemoteModLoader.loadConfiguredMod();
        } catch (Exception exception) {
            LOGGER.error("Failed to load remote mod during pre-launch", exception);
        }
    }
}
