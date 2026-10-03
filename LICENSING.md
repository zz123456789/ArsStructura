# Ars Structura licensing

Copyright (c) 2026 zz123456789 and Ars Structura contributors.

Ars Structura's original source code, original assets, documentation, and release artifacts are licensed under the **GNU Lesser General Public License, version 3 only** (`LGPL-3.0-only`). This grant also applies to the original Ars Structura material in previously distributed 0.1.0 artifacts, superseding their earlier All Rights Reserved metadata. Existing platform binaries are not silently replaced by this licensing change.

You may redistribute and/or modify the covered work under the terms of version 3 of the GNU Lesser General Public License as published by the Free Software Foundation. This grant does not include the option to use a later license version. The work is distributed without any warranty; see [LICENSE.txt](LICENSE.txt) and the incorporated GNU GPL v3 terms in [COPYING](COPYING).

## Third-party material

- Minecraft Forge and its MDK remain under their upstream licenses. The original MDK notice is preserved in [third_party/forge/LICENSE.txt](third_party/forge/LICENSE.txt); the original Forge credits remain in `CREDITS.txt`. The addon license does not relicense these files.
- Gradle wrapper and other third-party build infrastructure retain their upstream licensing.
- Ars Nouveau is a separately installed dependency. The license file in the verified 4.12.7 source commit contains LGPL v3: https://github.com/baileyholl/Ars-Nouveau/blob/2c74064bc753600d9b6600f102f8163e2d6764dd/license.txt . Its platform metadata is inconsistent with that file; this statement identifies the source document actually checked.
- Ars Nouveau assets remain the property of their authors. The tablet model JSON files reference `ars_nouveau:item/ritual_restoration` and `ars_nouveau:item/ritual_binding` at runtime. No Ars Nouveau texture, model, source file, or dependency JAR is included in this source repository or the addon JAR. This license grants no rights to those upstream assets.
- Other separately installed dependencies, Minecraft, and Mojang mappings retain their respective licenses. Local dependency JARs are excluded from version control and release packaging.
- The `publishing/icon.svg` project logo is original Ars Structura vector artwork covered by the addon license. Gameplay screenshots show Minecraft and third-party assets in context; those underlying assets are not relicensed.

Source: https://github.com/zz123456789/ArsStructura
