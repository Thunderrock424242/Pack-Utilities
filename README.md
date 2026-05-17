# Pack Utilities

A NeoForge 1.21.1 mod project.

## Development

- Java: 21
- Minecraft: 1.21.1
- NeoForge: 21.1.228

Build with:

```powershell
.\gradlew.bat build
```

## Commands

- `/datapackdoctor scan` - summarize recipe, loot table, tag, and worldgen issues.
- `/datapackdoctor recipes` - show recipe overrides and missing recipe references.
- `/datapackdoctor loot` - show loot table overrides and missing loot references.
- `/datapackdoctor tags` - show item/block tag values that point at missing IDs.
- `/datapackdoctor worldgen` - summarize loaded worldgen JSON folders and syntax problems.
- `/recipefix conflicts` - find recipe ID overrides and outputs made by multiple recipes.
- `/recipefix output <item>` - list recipes that create an item, like `minecraft:stick`.
- `/recipefix missing` - explain recipes that reference missing items or tags.
- Recipe reports are also appended to `logs/pack_utilities/recipefix.log`.
- `/lootdoctor inspect` - look at a container and explain its loot table status.
- `/lootdoctor simulate <loot_table>` - roll a loot table without touching a real chest.
- `/lootdoctor missing` - explain loot tables that reference missing items or tables.
