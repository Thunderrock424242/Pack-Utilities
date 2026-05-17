package com.thunder.packutilities;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class LootDoctorCommands {
    private LootDoctorCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("lootdoctor")
            .then(Commands.literal("inspect")
                .executes(context -> lootInspect(context.getSource())))
            .then(Commands.literal("simulate")
                .then(Commands.argument("table", ResourceLocationArgument.id())
                    .executes(context -> lootSimulate(
                        context.getSource(),
                        ResourceLocationArgument.getId(context, "table")
                    ))))
            .then(Commands.literal("missing")
                .executes(context -> lootMissing(context.getSource()))));
    }

    static int lootInspect(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(8.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a chest, barrel, hopper, dispenser, or other container first."));
            return 0;
        }

        BlockPos pos = ((BlockHitResult)hit).getBlockPos();
        BlockEntity blockEntity = player.serverLevel().getBlockEntity(pos);
        if (blockEntity == null) {
            source.sendFailure(Component.literal("No block entity found at " + pos.toShortString() + "."));
            return 0;
        }

        List<String> lines = new ArrayList<>();
        lines.add("Block: " + BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()));
        lines.add("Position: " + pos.toShortString());

        if (blockEntity instanceof RandomizableContainer randomizable) {
            ResourceKey<LootTable> lootTable = randomizable.getLootTable();
            if (lootTable == null) {
                lines.add("Loot table: none");
                lines.add("Status: generated or manually filled");
                if (blockEntity instanceof Container container && container.isEmpty()) {
                    lines.add("Possible issue: container has already generated and is currently empty");
                }
            } else {
                ResourceLocation id = lootTable.location();
                boolean exists = lootTableExists(source.getServer(), id);
                lines.add("Loot table: " + id);
                lines.add("Status: " + (exists ? "valid" : "missing"));
                lines.add("Generated: no");
                if (!exists) {
                    lines.add("Possible issue: the structure points at a loot table that is not loaded");
                }
            }
        } else if (blockEntity instanceof Container container) {
            lines.add("Loot table: none");
            lines.add("Status: normal container");
            lines.add("Empty: " + yesNo(container.isEmpty()));
        } else {
            lines.add("Status: this block entity is not a container");
        }

        PackDoctorResources.sendReport(source, "Loot Table Doctor inspect", lines);
        return 1;
    }

    static int lootSimulate(CommandSourceStack source, ResourceLocation tableId) {
        if (!lootTableExists(source.getServer(), tableId)) {
            source.sendFailure(Component.literal("Loot table is not loaded: " + tableId));
            return 0;
        }

        ServerLevel level = source.getLevel();
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, tableId);
        LootTable table = source.getServer().reloadableRegistries().getLootTable(key);
        LootParams.Builder builder = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, source.getPosition());

        if (source.getEntity() instanceof ServerPlayer player) {
            builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
        }

        List<ItemStack> stacks = table.getRandomItems(builder.create(LootContextParamSets.CHEST), System.nanoTime());
        List<String> lines = new ArrayList<>();
        lines.add("Loot table: " + tableId);
        if (stacks.isEmpty()) {
            lines.add("Generated: no item stacks");
            lines.add("Possible issue: table can roll empty, has unmet conditions, or has no pools");
        } else {
            lines.add("Generated " + stacks.size() + " stack(s):");
            stacks.stream().limit(PackDoctorResources.DETAIL_LIMIT).forEach(stack -> lines.add("- " + stack.getCount() + "x "
                + BuiltInRegistries.ITEM.getKey(stack.getItem())));
            PackDoctorResources.addRemaining(lines, stacks.size(), PackDoctorResources.DETAIL_LIMIT);
        }

        PackDoctorResources.sendReport(source, "Loot simulation", lines);
        return stacks.size();
    }

    static int lootMissing(CommandSourceStack source) {
        List<PackDoctorResources.ResourceIssue> issues = findLootMissingReferences(source.getServer());
        List<String> lines = new ArrayList<>();
        PackDoctorResources.appendIssues(lines, "Missing loot references:", issues);
        PackDoctorResources.sendReport(source, "Loot missing report", lines);
        return issues.size();
    }

    static List<PackDoctorResources.ResourceIssue> findLootMissingReferences(MinecraftServer server) {
        ResourceManager resources = server.getResourceManager();
        Registry<Item> itemRegistry = server.registryAccess().lookupOrThrow(Registries.ITEM);
        Set<ResourceLocation> loadedLootTables = new LinkedHashSet<>(server.reloadableRegistries().getKeys(Registries.LOOT_TABLE));
        loadedLootTables.addAll(PackDoctorResources.listIds(resources, PackDoctorResources.LOOT_TABLE_FILES));
        List<PackDoctorResources.ResourceIssue> issues = new ArrayList<>(
            PackDoctorResources.findJsonReadIssues(resources, PackDoctorResources.LOOT_TABLE_FILES, "loot table")
        );

        PackDoctorResources.readTopJsonResources(resources, PackDoctorResources.LOOT_TABLE_FILES).forEach((id, jsonResource) -> {
            Set<ResourceLocation> missingItems = new LinkedHashSet<>();
            Set<ResourceLocation> missingTables = new LinkedHashSet<>();
            collectLootReferences(jsonResource.json(), missingItems, missingTables, itemRegistry, loadedLootTables);

            missingItems.forEach(item -> issues.add(new PackDoctorResources.ResourceIssue(
                id,
                "Missing item " + item,
                "The loot entry points at an item that is not registered."
            )));
            missingTables.forEach(table -> issues.add(new PackDoctorResources.ResourceIssue(
                id,
                "Missing loot table " + table,
                "Create that loot table or fix the referenced ID."
            )));
        });

        return issues;
    }

    private static void collectLootReferences(
        JsonElement element,
        Set<ResourceLocation> missingItems,
        Set<ResourceLocation> missingTables,
        Registry<Item> itemRegistry,
        Set<ResourceLocation> loadedLootTables
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectLootReferences(child, missingItems, missingTables, itemRegistry, loadedLootTables));
            return;
        }

        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        String type = PackDoctorResources.getString(object, "type").orElse("");
        Optional<ResourceLocation> name = PackDoctorResources.getString(object, "name").flatMap(PackDoctorResources::tryParseId);
        boolean itemEntry = name.isPresent() && (type.endsWith(":item") || type.equals("item"));
        if (itemEntry && !itemRegistry.containsKey(name.get())) {
            missingItems.add(name.get());
        }

        boolean tableEntry = name.isPresent() && (type.endsWith(":loot_table") || type.equals("loot_table"));
        if (tableEntry && !loadedLootTables.contains(name.get())) {
            missingTables.add(name.get());
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectLootReferences(entry.getValue(), missingItems, missingTables, itemRegistry, loadedLootTables);
        }
    }

    private static boolean lootTableExists(MinecraftServer server, ResourceLocation id) {
        Collection<ResourceLocation> lootTables = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE);
        return lootTables.contains(id) || PackDoctorResources.listIds(server.getResourceManager(), PackDoctorResources.LOOT_TABLE_FILES).contains(id);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }
}
