# Litematica compatibility

The codec implements the documented behavior of the official `maruohon/litematica`
Minecraft 1.20.1 branch, inspected at commit
`afb3feb258aacdc52901217bec233e2bee880b21`. It does not load Litematica classes or
require Fabric. No Ars Nouveau or Litematica source is modified.

Authoritative references:

- [LitematicaSchematic.java](https://github.com/maruohon/litematica/blob/afb3feb258aacdc52901217bec233e2bee880b21/src/main/java/fi/dy/masa/litematica/schematic/LitematicaSchematic.java): version constants, gzip NBT, region and block entity serialization.
- [LitematicaBlockStateContainer.java](https://github.com/maruohon/litematica/blob/afb3feb258aacdc52901217bec233e2bee880b21/src/main/java/fi/dy/masa/litematica/schematic/container/LitematicaBlockStateContainer.java): palette bits and block indexing.
- [LitematicaBitArray.java](https://github.com/maruohon/litematica/blob/afb3feb258aacdc52901217bec233e2bee880b21/src/main/java/fi/dy/masa/litematica/schematic/container/LitematicaBitArray.java): continuous packed bits, including entries crossing long boundaries.
- [SchematicMetadata.java](https://github.com/maruohon/litematica/blob/afb3feb258aacdc52901217bec233e2bee880b21/src/main/java/fi/dy/masa/litematica/schematic/SchematicMetadata.java): metadata field names/types.
- [PositionUtils.java](https://github.com/maruohon/litematica/blob/afb3feb258aacdc52901217bec233e2bee880b21/src/main/java/fi/dy/masa/litematica/util/PositionUtils.java): signed region dimensions.

## Supported scope

- Read gzip `.litematic` Version 5 or 6, SubVersion absent/0/1, MinecraftDataVersion
  exactly 3465 (Minecraft 1.20.1). Export Version 6, SubVersion 1. Older/newer game
  versions require conversion in Litematica first; silently replacing unknown
  states with air is prohibited.
- Read all regions, including negative signed dimensions. Preserve spaces between
  regions and normalize their combined minimum corner to the tablet anchor.
- Conflicting saved blocks at overlapping coordinates are rejected. Identical
  overlaps are deduplicated. Air is omitted unless server `saveAir` is enabled.
- Preserve registered block states and permitted block entity data. Entities,
  scheduled ticks, placement transforms, and preview thumbnails are not imported.
- Exports contain one positive-size `Construction` region. Extra root compound
  `ArsStructura` supplies mod/format versions. External files without it display
  `unknown` for their mod version; this is not an official Litematica field.

## Coordinates and packed states

For each axis, `end = Position + (Size > 0 ? Size - 1 : Size + 1)` and
`regionMin = min(Position, end)`. Array coordinates and block entity `x/y/z`
start at `regionMin`, **not** at `Position`. Array order is
`index = (y * absSizeZ + z) * absSizeX + x`.

`bits = max(2, ceil(log2(paletteSize)))`; array length is
`ceil(volume * bits / 64)`. Indices form a continuous low-bit-first bitstream.
Modern Minecraft padded `SimpleBitStorage` is incompatible. The tests include
5-bit and 3-bit entries crossing word boundaries and negative/multiple regions.

## Resource and trust limits

Both native `.nbt` and `.litematic` pass through `SafeNbt`: compressed hard cap
1 MiB, configurable expanded/allocation quota (default 32 MiB), and configured
depth (default 32). A bounded wire preflight checks array/list lengths and depth
before Minecraft allocates the tree. Gzip expansion is limited independently.

Sparse tablet dimensions may reach 640 per axis, subject to the volume, block
count and byte quotas. Dense Litematic work is additionally limited to
`min(maxVolume, maxNbtBytes / 8)` cells (4,194,304 by default), counted across all
regions. Very sparse but enormous `.litematic` files may therefore be rejected;
this prevents a tiny compressed air-only file from requiring hundreds of millions
of main-thread operations. Native tablet format keeps sparse positions.

Block identifiers/properties are strictly checked against the runtime registry.
Block entities must have an allowed registered type valid for the block state.
Inventories, loot references and Forge capability payloads are removed by default.
Executable sign/text `run_command` click events are rejected, including escaped
JSON text; JSON depth is checked while streaming. Server validation remains
authoritative after a client file has been read.

Native `.nbt` format 1 includes `MinecraftDataVersion`, `ModVersion`, `Name`,
`Author`, `CreatedAt`, `SizeX/Y/Z`, and a `Blocks` compound list. Each block has
`Pos` int[3], `State` vanilla state compound and optional `BlockEntity` compound.
The two file formats are genuinely encoded independently; renaming a file
extension does not convert its format.
