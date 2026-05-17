package com.thunder.packutilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class DataPackDoctorCommands {
    private DataPackDoctorCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("datapackdoctor")
            .then(Commands.literal("scan")
                .executes(context -> datapackScan(context.getSource())))
            .then(Commands.literal("recipes")
                .executes(context -> datapackRecipes(context.getSource())))
            .then(Commands.literal("loot")
                .executes(context -> datapackLoot(context.getSource())))
            .then(Commands.literal("tags")
                .executes(context -> datapackTags(context.getSource())))
            .then(Commands.literal("worldgen")
                .executes(context -> datapackWorldgen(context.getSource()))));
    }

    private static int datapackScan(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ResourceManager resources = server.getResourceManager();
        List<PackDoctorResources.ResourceIssue> recipeMissing = RecipeFixCommands.findRecipeMissingReferences(server);
        List<PackDoctorResources.ResourceIssue> lootMissing = LootDoctorCommands.findLootMissingReferences(server);
        List<PackDoctorResources.ResourceIssue> tagMissing = findTagMissingReferences(server);
        List<PackDoctorResources.OverrideIssue> recipeOverrides = PackDoctorResources.findOverrides(resources, PackDoctorResources.RECIPE_FILES);
        List<PackDoctorResources.OverrideIssue> lootOverrides = PackDoctorResources.findOverrides(resources, PackDoctorResources.LOOT_TABLE_FILES);
        int recipeCount = PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.RECIPE_FILES).size();
        int lootCount = PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.LOOT_TABLE_FILES).size();
        int itemTagCount = PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.ITEM_TAG_FILES).size();
        int blockTagCount = PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.BLOCK_TAG_FILES).size();
        int worldgenCount = resources.listResources("worldgen", PackDoctorResources.isJson()).size();

        List<String> lines = new ArrayList<>();
        lines.add("Recipes: " + recipeCount + " loaded, " + recipeOverrides.size() + " override(s), "
            + recipeMissing.size() + " missing reference(s)");
        lines.add("Loot tables: " + lootCount + " loaded, " + lootOverrides.size() + " override(s), "
            + lootMissing.size() + " missing reference(s)");
        lines.add("Tags: " + (itemTagCount + blockTagCount) + " loaded, " + tagMissing.size() + " missing value(s)");
        lines.add("Worldgen JSON files: " + worldgenCount);
        lines.add("Drilldowns: /datapackdoctor recipes, loot, tags, worldgen");
        PackDoctorResources.sendReport(source, "DataPack Doctor scan", lines);
        return recipeMissing.size() + lootMissing.size() + tagMissing.size() + recipeOverrides.size() + lootOverrides.size();
    }

    private static int datapackRecipes(CommandSourceStack source) {
        int conflicts = RecipeFixCommands.recipeConflicts(source);
        int missing = RecipeFixCommands.recipeMissing(source);
        return conflicts + missing;
    }

    private static int datapackLoot(CommandSourceStack source) {
        ResourceManager resources = source.getServer().getResourceManager();
        List<String> lines = new ArrayList<>();
        List<PackDoctorResources.OverrideIssue> overrides = PackDoctorResources.findOverrides(resources, PackDoctorResources.LOOT_TABLE_FILES);
        if (overrides.isEmpty()) {
            lines.add("No loot table ID overrides found.");
        } else {
            lines.add("Loot table ID overrides:");
            overrides.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(issue ->
                lines.add("- " + issue.id() + " from " + String.join(" -> ", issue.packs())));
            PackDoctorResources.addRemaining(lines, overrides.size(), PackDoctorResources.DETAIL_LIMIT);
        }

        List<PackDoctorResources.ResourceIssue> missing = LootDoctorCommands.findLootMissingReferences(source.getServer());
        PackDoctorResources.appendIssues(lines, "Missing loot references:", missing);
        PackDoctorResources.sendReport(source, "Loot Doctor report", lines);
        return overrides.size() + missing.size();
    }

    private static int datapackTags(CommandSourceStack source) {
        List<PackDoctorResources.ResourceIssue> issues = findTagMissingReferences(source.getServer());
        List<String> lines = new ArrayList<>();
        PackDoctorResources.appendIssues(lines, "Missing tag values:", issues);
        PackDoctorResources.sendReport(source, "Tag report", lines);
        return issues.size();
    }

    private static int datapackWorldgen(CommandSourceStack source) {
        Map<ResourceLocation, Resource> files = PackDoctorResources.sortedResources(source.getServer().getResourceManager()
            .listResources("worldgen", PackDoctorResources.isJson()));
        Map<String, Integer> groups = new TreeMap<>();
        List<String> parseErrors = new ArrayList<>();

        for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
            String path = entry.getKey().getPath();
            String group = path.substring("worldgen/".length());
            int slash = group.indexOf('/');
            groups.merge(slash >= 0 ? group.substring(0, slash) : group, 1, Integer::sum);
            try {
                PackDoctorResources.readJson(entry.getValue());
            } catch (IOException | JsonParseException exception) {
                parseErrors.add(entry.getKey() + ": " + exception.getMessage());
            }
        }

        List<String> lines = new ArrayList<>();
        if (groups.isEmpty()) {
            lines.add("No worldgen JSON resources found.");
        } else {
            lines.add("Worldgen resources by folder:");
            groups.forEach((folder, count) -> lines.add("- " + folder + ": " + count));
        }

        if (parseErrors.isEmpty()) {
            lines.add("JSON syntax: no parse problems found in loaded resources.");
        } else {
            lines.add("JSON syntax problems:");
            parseErrors.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(error -> lines.add("- " + error));
            PackDoctorResources.addRemaining(lines, parseErrors.size(), PackDoctorResources.DETAIL_LIMIT);
        }

        PackDoctorResources.sendReport(source, "Worldgen report", lines);
        return parseErrors.size();
    }

    private static List<PackDoctorResources.ResourceIssue> findTagMissingReferences(MinecraftServer server) {
        ResourceManager resources = server.getResourceManager();
        Registry<Item> itemRegistry = server.registryAccess().lookupOrThrow(Registries.ITEM);
        Registry<?> blockRegistry = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        Set<ResourceLocation> itemTags = PackDoctorResources.listIds(resources, PackDoctorResources.ITEM_TAG_FILES);
        Set<ResourceLocation> blockTags = PackDoctorResources.listIds(resources, PackDoctorResources.BLOCK_TAG_FILES);
        List<PackDoctorResources.ResourceIssue> issues = new ArrayList<>();
        issues.addAll(PackDoctorResources.findJsonReadIssues(resources, PackDoctorResources.ITEM_TAG_FILES, "item tag"));
        issues.addAll(PackDoctorResources.findJsonReadIssues(resources, PackDoctorResources.BLOCK_TAG_FILES, "block tag"));

        scanTagFiles(PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.ITEM_TAG_FILES), itemRegistry::containsKey, itemTags, issues, "item");
        scanTagFiles(PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.BLOCK_TAG_FILES), blockRegistry::containsKey, blockTags, issues, "block");
        return issues;
    }

    private static void scanTagFiles(
        Map<ResourceLocation, PackDoctorResources.JsonResource> tags,
        java.util.function.Predicate<ResourceLocation> valueExists,
        Set<ResourceLocation> knownTags,
        List<PackDoctorResources.ResourceIssue> issues,
        String type
    ) {
        tags.forEach((id, jsonResource) -> {
            JsonElement values = jsonResource.json().get("values");
            if (!(values instanceof JsonArray array)) {
                issues.add(new PackDoctorResources.ResourceIssue(id, "Tag has no values array", "Check the tag JSON shape."));
                return;
            }

            for (JsonElement value : array) {
                Optional<TagReference> reference = readTagReference(value);
                if (reference.isEmpty() || !reference.get().required()) {
                    continue;
                }

                TagReference tagReference = reference.get();
                if (tagReference.tag()) {
                    if (!knownTags.contains(tagReference.id())) {
                        issues.add(new PackDoctorResources.ResourceIssue(id, "Missing " + type + " tag #" + tagReference.id(), "Create the nested tag or mark it as optional."));
                    }
                } else if (!valueExists.test(tagReference.id())) {
                    issues.add(new PackDoctorResources.ResourceIssue(id, "Missing " + type + " " + tagReference.id(), "Install the mod that owns this value or mark it as optional."));
                }
            }
        });
    }

    private static Optional<TagReference> readTagReference(JsonElement value) {
        boolean required = true;
        String id;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            id = value.getAsString();
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            id = PackDoctorResources.getString(object, "id").orElse("");
            required = !object.has("required") || object.get("required").getAsBoolean();
        } else {
            return Optional.empty();
        }

        boolean tag = id.startsWith("#");
        String normalized = tag ? id.substring(1) : id;
        return PackDoctorResources.tryParseId(normalized).map(parsed -> new TagReference(parsed, tag, required));
    }

    private record TagReference(ResourceLocation id, boolean tag, boolean required) {
    }
}
