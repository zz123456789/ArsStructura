## Ars Structura 0.1.0 — Initial public beta

- Add Construction and Prefab Construction Tablets for Ars Nouveau 4.12.7 on Minecraft 1.20.1 / Forge.
- Capture buildings with two selection points, distinct colored outlines, and translucent ghost previews.
- Build through the native Ritual Brazier activation flow with configurable channeling time, Source costs, and safe replacement rules.
- Save and import local NBT and Minecraft 1.20.1 Litematica-format prefabs through the prefab library.
- Validate imports and server-side actions, preserve item data and ritual progress, and restrict unsafe blocks and block-entity data.
- Include the fix for passive preview requests racing with hotbar slot synchronization.

This public version renumbers the tested development build 1.0.2 to 0.1.0; it is not a feature rollback. Both client and server require the addon, Ars Nouveau, and Ars Nouveau's dependencies.

Core UI workflows were tested in the development client. Full modpack, two-client multiplayer, and external Litematica cross-reading remain pending. See the project description for supported formats and limits.
