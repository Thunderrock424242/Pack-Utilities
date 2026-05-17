package com.thunder.packutilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

final class PackDoctorResources {
    static final FileToIdConverter RECIPE_FILES = FileToIdConverter.registry(Registries.RECIPE);
    static final FileToIdConverter LOOT_TABLE_FILES = FileToIdConverter.registry(Registries.LOOT_TABLE);
    static final FileToIdConverter ITEM_TAG_FILES = FileToIdConverter.json(Registries.tagsDirPath(Registries.ITEM));
    static final FileToIdConverter BLOCK_TAG_FILES = FileToIdConverter.json(Registries.tagsDirPath(Registries.BLOCK));
    static final int DETAIL_LIMIT = 12;

    private PackDoctorResources() {
    }

    static Map<ResourceLocation, JsonResource> readTopJsonResources(ResourceManager resources, FileToIdConverter converter) {
        Map<ResourceLocation, JsonResource> output = new LinkedHashMap<>();
        sortedResources(converter.listMatchingResources(resources)).forEach((file, resource) -> {
            try {
                JsonElement json = readJson(resource);
                if (json.isJsonObject()) {
                    output.put(converter.fileToId(file), new JsonResource(file, resource, json.getAsJsonObject()));
                }
            } catch (IOException | JsonParseException exception) {
                PackUtilities.LOGGER.warn("Could not read {} from {} for Pack Utilities scan", file, resource.sourcePackId(), exception);
            }
        });
        return output;
    }

    static JsonElement readJson(Resource resource) throws IOException, JsonParseException {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        }
    }

    static List<ResourceIssue> findJsonReadIssues(ResourceManager resources, FileToIdConverter converter, String kind) {
        List<ResourceIssue> issues = new ArrayList<>();
        sortedResources(converter.listMatchingResources(resources)).forEach((file, resource) -> {
            try {
                readJson(resource);
            } catch (IOException | JsonParseException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                issues.add(new ResourceIssue(
                    converter.fileToId(file),
                    "Invalid " + kind + " JSON from " + resource.sourcePackId() + ": " + message,
                    "Fix the JSON syntax or remove the broken file, then run /reload."
                ));
            }
        });
        return issues;
    }

    static List<OverrideIssue> findOverrides(ResourceManager resources, FileToIdConverter converter) {
        return sortedStacks(converter.listMatchingResourceStacks(resources)).entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> new OverrideIssue(
                converter.fileToId(entry.getKey()),
                entry.getValue().stream().map(Resource::sourcePackId).distinct().toList()
            ))
            .sorted(Comparator.comparing(issue -> issue.id().toString()))
            .toList();
    }

    static Set<ResourceLocation> listIds(ResourceManager resources, FileToIdConverter converter) {
        return converter.listMatchingResources(resources).keySet().stream()
            .map(converter::fileToId)
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<String> collectByKey(JsonElement element, String key) {
        Set<String> values = new LinkedHashSet<>();
        collectByKey(element, key, values);
        return values;
    }

    private static void collectByKey(JsonElement element, String key, Set<String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectByKey(child, key, values));
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals(key) && entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                values.add(entry.getValue().getAsString());
            }
            collectByKey(entry.getValue(), key, values);
        }
    }

    static Optional<ResourceLocation> tryParseId(String id) {
        try {
            return Optional.ofNullable(ResourceLocation.tryParse(id));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static Optional<String> getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return Optional.of(element.getAsString());
        }
        return Optional.empty();
    }

    static Predicate<ResourceLocation> isJson() {
        return id -> id.getPath().endsWith(".json");
    }

    static Map<ResourceLocation, Resource> sortedResources(Map<ResourceLocation, Resource> input) {
        return input.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    private static Map<ResourceLocation, List<Resource>> sortedStacks(Map<ResourceLocation, List<Resource>> input) {
        return input.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    static void appendIssues(List<String> lines, String heading, List<ResourceIssue> issues) {
        if (issues.isEmpty()) {
            lines.add(heading.replace(":", "") + ": none found.");
            return;
        }

        lines.add(heading);
        issues.stream().limit(DETAIL_LIMIT).forEach(issue -> {
            lines.add("- " + issue.resource() + ": " + issue.problem());
            lines.add("  Fix: " + issue.fix());
        });
        addRemaining(lines, issues.size(), DETAIL_LIMIT);
    }

    static void addRemaining(List<String> lines, int total, int shown) {
        if (total > shown) {
            lines.add("- and " + (total - shown) + " more...");
        }
    }

    static void sendReport(CommandSourceStack source, String title, List<String> lines) {
        source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.GOLD), false);
        for (String line : lines) {
            ChatFormatting style = line.startsWith("  Fix:") ? ChatFormatting.GRAY : ChatFormatting.WHITE;
            source.sendSuccess(() -> Component.literal(line).withStyle(style), false);
        }
    }

    record JsonResource(ResourceLocation file, Resource resource, JsonObject json) {
    }

    record OverrideIssue(ResourceLocation id, List<String> packs) {
    }

    record ResourceIssue(ResourceLocation resource, String problem, String fix) {
    }
}
