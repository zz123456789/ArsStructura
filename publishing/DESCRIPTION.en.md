## Overview

| Item | Details |
| --- | --- |
| Requirements | Minecraft Java Edition 1.20.1 · Forge 47.4.10 or a compatible later 47.x release · Java 17 |
| Dependency | Ars Nouveau 4.12.7 (supported dependency range: 4.12.7 to below 4.13), with its required dependencies |
| Installation | Required on both the client and the server |
| Items | Construction Tablet and Prefab Construction Tablet |
| File formats | Native `.nbt` prefabs and Minecraft 1.20.1 `.litematic` files |
| License | LGPL-3.0-only |

**Ars Structura** lets you capture a building, preview it as translucent ghost blocks, and rebuild it with Source through an Ars Nouveau Ritual Brazier. Two tablets use the familiar Ars ritual-tablet appearance: a cyan Construction Tablet and a purple Prefab Construction Tablet.

Find both items in the **Ars Structura** creative inventory tab, or craft them in survival. The prefab tablet adds a local file library for saving and sharing buildings.

## How to use

| Action | Result |
| --- | --- |
| Sneak + left-click a block | Set point 1 without breaking the block. A cyan outline marks the position. |
| Sneak + right-click a block | Set point 2. An orange outline marks the position, and a gold box outlines the selection. The tablet captures the structure. |
| Right-click a block normally | Set the target anchor beside the clicked face and show a translucent preview. Clicking the top face places the anchor above the block. |
| Right-click air with the Construction Tablet | Cancel its preview. |
| Right-click air with the Prefab Construction Tablet | Open the prefab library. |

Select both corners in the same dimension. By default, the tablet saves non-air blocks, their block states, and supported block-entity data. Keep the selection loaded and the tablet held while capture completes.

The anchor is the structure's minimum corner. Ghost blocks are only a preview: they do not place blocks or add collisions. Switching away from the tablet hides the preview.

### Build with a Ritual Brazier

1. Capture or import a building, then set its target anchor.
2. Right-click an idle **Ars Nouveau Ritual Brazier** with the tablet. The brazier lights and waits for activation.
3. Switch to an **empty main hand** and right-click the brazier to start the ritual.
4. Wait for the default **5-second channeling period**. Construction then proceeds in batches.

Place a Source supply near the brazier. Default costs are **100 Source to load the ritual, plus 10 Source per block placed**. The tablet is kept, and no additional fuel item is required by default. If Source runs out during construction, the job pauses until more is available. The ritual creates blocks using Source; it does not consume building materials from your inventory.

Existing solid blocks are skipped by default. Supported facing, stair-half, log-axis, and block-entity data are restored. Container contents and loot tables are **not copied by default**. Only the player who loaded the ritual can activate it; redstone pauses the ritual.

### Crafting

| Tablet | Recipe |
| --- | --- |
| Construction Tablet | Rows: ` SG` / `SPS` / `GS ` — S: smooth stone, G: Source Gem, P: paper |
| Prefab Construction Tablet | Rows: ` GR` / `GTG` / `RG ` — G: gold ingot, R: redstone, T: Construction Tablet |

Upgrading creates a new, empty prefab tablet. Upgrade first, then capture or import your building.

## Saving and sharing buildings

Right-click air with the prefab tablet to open a Screen that does not pause the game by default. It shows the saved size, block count, selection points, anchor, and dimensions, alongside a list of local files.

| Button | What it does |
| --- | --- |
| Save NBT | Save the tablet's current building as a native `.nbt` prefab. |
| Export | Export the current building in the selected `.nbt` or `.litematic` format. |
| Import | Validate a selected file and load it into the held prefab tablet through the server. |
| Rename / Delete / Refresh | Manage the local library. Deletion requires a second click to confirm. |
| Preview | Show the current tablet's building at its saved anchor. Import a local file first to preview that file. |
| Clear tablet | Clear the tablet's saved structure, selection, and target. |

Local files are stored in your game instance's:

```text
config/ars_construction_tablet/prefabs/
```

Put a compatible file in this folder, refresh the library, select it, and import it. The server validates imported structures before changing the item. Servers do not read or write your local files directly.

**Litematica does not need to be installed.** This is file-format support: Minecraft 1.20.1 / DataVersion 3465, Litematica format versions 5 and 6. Exports use format 6. Multi-region layouts, negative-size regions, block-state palettes, and supported block entities are handled. Files from other Minecraft versions require conversion first. Entities and scheduled ticks are not copied.

## Configuration

Server settings are in the world's `serverconfig/arsstructura-server.toml`; client preview settings are in `config/arsstructura-client.toml`.

| Setting | Default |
| --- | --- |
| Maximum selection edge | 640 blocks |
| Maximum stored blocks | 65,536 |
| Maximum compressed structure/file | 1 MiB |
| Ritual channeling time | 100 ticks (5 seconds at normal tick speed) |
| Preview distance / opacity | 96 blocks / 35% |
| Maximum brazier-to-anchor distance | 256 blocks |
| Replace solid blocks / copy inventories | Disabled / disabled |

Limits apply together: a 640-block edge does not allow a tablet to store 640³ solid blocks. Source costs, optional fuel, tablet consumption, capture/build budgets, block restrictions, and preview limits are configurable. Dense Litematica files have an additional processing budget.

## Frequently asked questions

**Can I use this in multiplayer?**

Install the same version on both the client and server, together with Ars Nouveau and its dependencies. Item changes and construction are validated on the server; previews and file storage run on the client. Two-client multiplayer testing is still pending for this initial beta.

**Will it overwrite my existing buildings?**

Not by default: construction skips non-replaceable blocks. Even with forced replacement enabled, protected block entities, unbreakable blocks, and blacklisted blocks remain protected. Command blocks, structure blocks, bedrock, and other dangerous blocks are restricted. Protection integrations that use Forge placement/break events are consulted; individual protection mods still need testing.

**Does my building survive a restart?**

Tablets retain their structure and target. Ritual waiting, channeling, and construction progress are saved with the world. Jobs pause when their owner is offline or in another dimension, or when target chunks are unloaded. Removing the brazier cancels the job; already placed blocks and spent Source are not rolled back.

**How should I transfer buildings between worlds?**

Use prefab export and import. Creative-toolbar item summaries are not a substitute for a prefab file.

**What has been tested?**

Core selection, colored outlines, ghost previews, both tablets' brazier builds, local file operations, persistence, and invalid-file rejection were tested in a Forge 47.4.10 / Ars Nouveau 4.12.7 development client. Automated tests cover structure validation, file codecs, and ritual behavior. External Litematica cross-reading, full modpack combinations, shader/rendering replacements, and large-scale performance still need broader testing.

Original Ars Structura material is licensed **LGPL-3.0-only**, including version 0.1.0 previously distributed with older license metadata. Third-party assets retain their own licenses. [Source and licensing details](https://github.com/zz123456789/ArsStructura) · [Report an issue](https://github.com/zz123456789/ArsStructura/issues).
