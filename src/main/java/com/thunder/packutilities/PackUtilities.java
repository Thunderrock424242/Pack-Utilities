package com.thunder.packutilities;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(PackUtilities.MOD_ID)
public final class PackUtilities {
    public static final String MOD_ID = "pack_utilities";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PackUtilities(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(DataPackDoctorCommands::register);
        NeoForge.EVENT_BUS.addListener(RecipeFixCommands::register);
        NeoForge.EVENT_BUS.addListener(LootDoctorCommands::register);
        LOGGER.info("Pack Utilities loaded.");
    }
}
