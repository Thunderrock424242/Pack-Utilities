package com.thunder.packutilities;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class RecipeFixLogWriter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RecipeFixLogWriter() {
    }

    static void write(CommandSourceStack source, String title, List<String> lines) {
        Path path = source.getServer().getFile("logs").resolve(PackUtilities.MOD_ID).resolve("recipefix.log");
        List<String> output = new ArrayList<>();
        output.add("");
        output.add("==== " + title + " ====");
        output.add("Generated: " + LocalDateTime.now().format(TIMESTAMP));
        output.addAll(lines);

        try {
            Files.createDirectories(path.getParent());
            Files.write(path, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            source.sendSuccess(() -> Component.literal("Saved full recipe report to " + path), false);
        } catch (IOException exception) {
            PackUtilities.LOGGER.warn("Failed to write recipe report to {}", path, exception);
            source.sendFailure(Component.literal("Could not write recipe report: " + exception.getMessage()));
        }
    }
}
