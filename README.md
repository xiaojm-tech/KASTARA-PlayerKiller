# KASTARA-PlayerKiller 1.2.0

Paper plugin for KASTARA. Requires KASTARALives.

## Features
- Player head drops on PvP kill, with PDC marker and anti-helmet protection.
- Three progression stages and configurable shaped recipes.
- Stage 3 recipe: `HNH / SHC / DDD` (H=custom Player Head, N=Netherite Ingot, S=Diamond Sword, C=Diamond Chestplate, D=Diamond).
- `/recipe` opens a stage selector and 3x3 recipe preview.
- Activation animation, firework particles, configurable sound and final title.
- Permanent progression effects yield to stronger vanilla potion effects until those expire.
- Life-loss reset integration through KASTARALives 1.4.0.

## Texture setup
Set `textures.stage-1`, `textures.stage-2`, and `textures.stage-3` to a valid
`https://textures.minecraft.net/texture/<hash>` URL or Base64 texture property. Empty values use the owning player's skin / default head appearance.
The plugin can only apply genuine textures; lore labels are not a substitute for a texture pack.

## Install
1. Back up the existing plugin JAR and `plugins/KASTARA-PlayerKiller/` folder.
2. Replace the old JAR with the new build.
3. Start server once to generate the new config, or merge new keys into existing config.
4. Configure textures and restart.
5. Test `/recipe`, head equip prevention, crafting, activation, potion override and life reset.

Build: `gradle clean build --no-daemon`.
