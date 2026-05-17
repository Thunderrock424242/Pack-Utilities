package com.thunder.packutilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class RecipeFixCommands {
    private RecipeFixCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("recipefix")
            .then(Commands.literal("conflicts")
                .executes(context -> recipeConflicts(context.getSource())))
            .then(Commands.literal("output")
                .then(Commands.argument("item", ResourceLocationArgument.id())
                    .executes(context -> recipeOutput(
                        context.getSource(),
                        ResourceLocationArgument.getId(context, "item")
                    ))))
            .then(Commands.literal("missing")
                .executes(context -> recipeMissing(context.getSource()))));
    }

    static int recipeConflicts(CommandSourceStack source) {
        ResourceManager resources = source.getServer().getResourceManager();
        List<PackDoctorResources.OverrideIssue> overrides = PackDoctorResources.findOverrides(resources, PackDoctorResources.RECIPE_FILES);
        Map<ResourceLocation, List<ResourceLocation>> outputs = recipesByOutput(resources);
        List<Map.Entry<ResourceLocation, List<ResourceLocation>>> repeatedOutputs = outputs.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .sorted(Comparator.<Map.Entry<ResourceLocation, List<ResourceLocation>>>comparingInt(entry -> entry.getValue().size()).reversed()
                .thenComparing(entry -> entry.getKey().toString()))
            .toList();

        List<String> lines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        if (overrides.isEmpty()) {
            lines.add("No recipe ID overrides found.");
            logLines.add("No recipe ID overrides found.");
        } else {
            lines.add("Recipe ID overrides:");
            logLines.add("Recipe ID overrides:");
            overrides.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(issue ->
                lines.add("- " + issue.id() + " from " + String.join(" -> ", issue.packs())));
            PackDoctorResources.addRemaining(lines, overrides.size(), PackDoctorResources.DETAIL_LIMIT);
            overrides.forEach(issue -> logLines.add("- " + issue.id() + " from " + String.join(" -> ", issue.packs())));
        }

        if (repeatedOutputs.isEmpty()) {
            lines.add("No repeated recipe outputs found.");
            logLines.add("No repeated recipe outputs found.");
        } else {
            lines.add("Outputs created by multiple recipes:");
            logLines.add("Outputs created by multiple recipes:");
            repeatedOutputs.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(entry -> lines.add("- "
                + entry.getKey() + ": " + entry.getValue().size() + " recipes"));
            PackDoctorResources.addRemaining(lines, repeatedOutputs.size(), PackDoctorResources.DETAIL_LIMIT);
            lines.add("Use /recipefix output <item> for the recipe list behind one output.");
            repeatedOutputs.forEach(entry -> {
                logLines.add("- " + entry.getKey() + ": " + entry.getValue().size() + " recipes");
                entry.getValue().forEach(recipe -> logLines.add("  - " + recipe));
            });
        }

        PackDoctorResources.sendReport(source, "Recipe conflict report", lines);
        RecipeFixLogWriter.write(source, "Recipe conflict report", logLines);
        return overrides.size() + repeatedOutputs.size();
    }

    private static int recipeOutput(CommandSourceStack source, ResourceLocation output) {
        Map<ResourceLocation, List<ResourceLocation>> outputs = recipesByOutput(source.getServer().getResourceManager());
        List<ResourceLocation> recipes = outputs.getOrDefault(output, List.of());
        List<String> lines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();

        if (recipes.isEmpty()) {
            lines.add("No loaded recipe JSON creates " + output + ".");
            logLines.add("No loaded recipe JSON creates " + output + ".");
        } else {
            lines.add(recipes.size() + " recipe(s) create " + output + ":");
            logLines.add(recipes.size() + " recipe(s) create " + output + ":");
            recipes.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(recipe -> lines.add("- " + recipe));
            PackDoctorResources.addRemaining(lines, recipes.size(), PackDoctorResources.DETAIL_LIMIT);
            recipes.forEach(recipe -> logLines.add("- " + recipe));
        }

        PackDoctorResources.sendReport(source, "Recipe output lookup", lines);
        RecipeFixLogWriter.write(source, "Recipe output lookup: " + output, logLines);
        return recipes.size();
    }

    static int recipeMissing(CommandSourceStack source) {
        List<PackDoctorResources.ResourceIssue> issues = findRecipeMissingReferences(source.getServer());
        List<String> lines = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        PackDoctorResources.appendIssues(lines, "Missing recipe references:", issues);
        appendAllIssues(logLines, "Missing recipe references:", issues);
        PackDoctorResources.sendReport(source, "Recipe missing report", lines);
        RecipeFixLogWriter.write(source, "Recipe missing report", logLines);
        return issues.size();
    }

    static List<PackDoctorResources.ResourceIssue> findRecipeMissingReferences(MinecraftServer server) {
        ResourceManager resources = server.getResourceManager();
        Registry<Item> itemRegistry = server.registryAccess().lookupOrThrow(Registries.ITEM);
        Set<ResourceLocation> itemTags = PackDoctorResources.listIds(resources, PackDoctorResources.ITEM_TAG_FILES);
        List<PackDoctorResources.ResourceIssue> issues = new ArrayList<>(
            PackDoctorResources.findJsonReadIssues(resources, PackDoctorResources.RECIPE_FILES, "recipe")
        );

        PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.RECIPE_FILES).forEach((id, jsonResource) -> {
            Set<ResourceLocation> missingItems = PackDoctorResources.collectByKey(jsonResource.json(), "item").stream()
                .map(PackDoctorResources::tryParseId)
                .flatMap(Optional::stream)
                .filter(item -> !itemRegistry.containsKey(item))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            readRecipeOutput(jsonResource.json())
                .filter(output -> !itemRegistry.containsKey(output))
                .ifPresent(missingItems::add);

            Set<ResourceLocation> missingTags = PackDoctorResources.collectByKey(jsonResource.json(), "tag").stream()
                .map(PackDoctorResources::tryParseId)
                .flatMap(Optional::stream)
                .filter(tag -> !itemTags.contains(tag))
                .collect(Collectors.toCollection(LinkedHashSet::new));

            missingItems.forEach(item -> issues.add(new PackDoctorResources.ResourceIssue(
                id,
                "Missing item " + item,
                "Install the mod that owns this item, update the item ID, or remove the recipe."
            )));
            missingTags.forEach(tag -> issues.add(new PackDoctorResources.ResourceIssue(
                id,
                "Missing item tag #" + tag,
                "Add the tag file or install the pack/mod that provides it."
            )));
        });

        return issues;
    }

    private static Map<ResourceLocation, List<ResourceLocation>> recipesByOutput(ResourceManager resources) {
        Map<ResourceLocation, List<ResourceLocation>> outputs = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.RECIPE_FILES).forEach((id, jsonResource) ->
            readRecipeOutput(jsonResource.json()).ifPresent(output ->
                outputs.computeIfAbsent(output, ignored -> new ArrayList<>()).add(id)));
        outputs.values().forEach(list -> list.sort(Comparator.comparing(ResourceLocation::toString)));
        return outputs;
    }

    private static Optional<ResourceLocation> readRecipeOutput(JsonObject json) {
        JsonElement result = json.get("result");
        if (result == null) {
            return Optional.empty();
        }

        if (result.isJsonPrimitive() && result.getAsJsonPrimitive().isString()) {
            return PackDoctorResources.tryParseId(result.getAsString());
        }

        if (result.isJsonObject()) {
            JsonObject resultObject = result.getAsJsonObject();
            return PackDoctorResources.getString(resultObject, "id")
                .or(() -> PackDoctorResources.getString(resultObject, "item"))
                .flatMap(PackDoctorResources::tryParseId);
        }

        return Optional.empty();
    }

    private static void appendAllIssues(List<String> lines, String heading, List<PackDoctorResources.ResourceIssue> issues) {
        if (issues.isEmpty()) {
            lines.add(heading.replace(":", "") + ": none found.");
            return;
        }

        lines.add(heading);
        for (PackDoctorResources.ResourceIssue issue : issues) {
            lines.add("- " + issue.resource() + ": " + issue.problem());
            lines.add("  Fix: " + issue.fix());
        }
    }
}
