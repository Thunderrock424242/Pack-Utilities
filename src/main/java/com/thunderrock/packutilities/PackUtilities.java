package com.thunderrock.packutilities;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PackUtilities.MOD_ID)
public final class PackUtilities {
    public static final String MOD_ID = "pack_utilities";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackUtilities(IEventBus modEventBus) {
        LOGGER.info("Pack Utilities loaded.");
    }
}
