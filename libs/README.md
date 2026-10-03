# Development dependencies

These original JARs were copied from the user's specified Minecraft 1.20.1 Forge 47.4.22 instance. They are used by ForgeGradle for compilation/development and are **not embedded** into the addon JAR. Install them separately with Ars Structura on both sides.

| File | SHA-256 |
| --- | --- |
| `ars_nouveau-1.20.1-4.12.7-all.jar` | `1f1debc282a0c379c1141f2840ea294eede6f6b544c589663f40bbe17b59a1af` |
| `curios-forge-5.14.1+1.20.1.jar` | `1e817919a35b37cf30524aaec73f0ca5130452f23f168f844854df282eb8e51f` |
| `geckolib-forge-1.20.1-4.8.4.jar` | `c04abba4fd354dd8c0e8a732f1f05f4dca893d2e1d962b2ab128bbd411478747` |
| `Patchouli-1.20.1-85-FORGE.jar` | `05f7b5d52f6b8f0fd7f8b4822fa07a192d1394d83a264309c9d221d6d4fd21c5` |

The dependency refmaps are remapped for development runs through the standard Mixin runtime properties in `build.gradle`. No dependency code or JAR has been patched.
