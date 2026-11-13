package id.kastara.playerkiller;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.bukkit.Particle;
import org.bukkit.Sound;
import java.net.URL;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class KASTARAPlayerKiller extends JavaPlugin implements Listener {
    private NamespacedKey itemKey;
    private final Map<UUID, Integer> stages = new HashMap<>();
    private final Map<UUID, Integer> lifeSnapshot = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemDisplay>> stage2ShieldHalos = new HashMap<>();
    private final Set<UUID> firstHeadGuideSent = new HashSet<>();
    private Object lifeManager;
    private Method getLivesMethod;
    private BukkitTask pollTask;
    private final Map<UUID, Integer> recipeMenus = new HashMap<>();
    private static final String RECIPE_TITLE = "§8KASTARA • Recipes";

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "playerkiller_item");
        if (!hookLives()) {
            getLogger().severe("KASTARALives 1.4.0 tidak ditemukan / API getLifeManager tidak tersedia. Plugin dinonaktifkan.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadData();
        registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("recipe") != null) getCommand("recipe").setExecutor(this);

        Set<UUID> tracked = new HashSet<>(lifeSnapshot.keySet());
        tracked.addAll(stages.keySet());
        for (Player p : Bukkit.getOnlinePlayers()) tracked.add(p.getUniqueId());
        for (UUID id : tracked) {
            int now = readLives(id);
            Integer old = lifeSnapshot.put(id, now);
            if (old != null && old >= 0 && now >= 0 && now < old) {
                boolean hadProgress = stages.remove(id) != null;
                Player online = Bukkit.getPlayer(id);
                if (online != null) clearEffects(online, hadProgress);
            }
        }
        saveData();

        // KASTARALives 1.4.0 has setLives(UUID,int), but no public life-change event.
        // Polling detects admin /life set reductions, including while the player is offline.
        pollTask = Bukkit.getScheduler().runTaskTimer(this, this::pollLifeChanges, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                int stage = stages.getOrDefault(p.getUniqueId(), 0);
                if (stage > 0) applyStage(p, stage);
            }
        }, 20L, 20L);

        // Efek redstone dust dekoratif di lantai sekitar kepala yang sedang dipajang.
        Bukkit.getScheduler().runTaskTimer(this, this::spawnPlacedHeadRedstone, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateStageFloorVisuals, 5L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateStage2ShieldHalos, 1L, 1L);
        getLogger().info("KASTARA-PlayerKiller enabled; hooked into KASTARALives.");
    }

    @Override public void onDisable() {
        if (pollTask != null) pollTask.cancel();
        for (List<ItemDisplay> displays : stage2ShieldHalos.values())
            for (ItemDisplay display : displays) if (display.isValid()) display.remove();
        stage2ShieldHalos.clear();
        saveData();
    }

    private boolean hookLives() {
        Plugin lives = Bukkit.getPluginManager().getPlugin("KASTARALives");
        if (lives == null) return false;
        try {
            Method getter = lives.getClass().getMethod("getLifeManager");
            lifeManager = getter.invoke(lives);
            getLivesMethod = lifeManager.getClass().getMethod("getLives", UUID.class);
            return true;
        } catch (ReflectiveOperationException ex) {
            getLogger().severe("Gagal menghubungkan API KASTARALives: " + ex.getMessage());
            return false;
        }
    }

    private int readLives(UUID id) {
        try { return ((Number) getLivesMethod.invoke(lifeManager, id)).intValue(); }
        catch (ReflectiveOperationException ex) {
            getLogger().warning("Gagal membaca life " + id + ": " + ex.getMessage());
            return -1;
        }
    }

    private void pollLifeChanges() {
        Set<UUID> tracked = new HashSet<>(lifeSnapshot.keySet());
        tracked.addAll(stages.keySet());
        for (Player p : Bukkit.getOnlinePlayers()) tracked.add(p.getUniqueId());
        boolean changed = false;
        for (UUID id : tracked) {
            int now = readLives(id);
            Integer old = lifeSnapshot.put(id, now);
            if (old != null && old >= 0 && now >= 0 && now < old) {
                boolean hadProgress = stages.remove(id) != null;
                Player online = Bukkit.getPlayer(id);
                if (online != null) clearEffects(online, hadProgress);
                changed = true;
            }
        }
        if (changed) saveData();
    }

    private void clearEffects(Player p, boolean notify) {
        p.removePotionEffect(PotionEffectType.STRENGTH);
        p.removePotionEffect(PotionEffectType.RESISTANCE);
        if (notify) {
            p.sendMessage(color(msg("reset-message", "&cNyawa berkurang! Semua efek dan progres Break your limit telah di-reset.")));
            p.sendActionBar(color(msg("reset-actionbar", "&cProgres Break your limit di-reset")));
        }
    }

    private void resetProgress(Player p) {
        boolean hadProgress = stages.remove(p.getUniqueId()) != null;
        clearEffects(p, hadProgress);
        saveData();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        resetProgress(victim);
        if (killer == null || killer.equals(victim)) return;

        // Pemain yang sudah mencapai Player/Killer tidak lagi menghasilkan Player Head.
        if (stages.getOrDefault(killer.getUniqueId(), 0) >= 3) {
            killer.sendMessage(color(msg("final-stage-no-head", "&cAbsolute Unit &7sudah mencapai puncak. Kill berikutnya tidak menjatuhkan Player Head.")));
            return;
        }

        victim.getWorld().dropItemNaturally(victim.getLocation(), makePlayerHead(victim));
        if (firstHeadGuideSent.add(killer.getUniqueId())) {
            saveData();
            sendGuide(killer);
        } else {
            killer.sendMessage(color(msg("head-drop-message", "&aKamu mendapatkan &fPlayer Head&a! Cek progres crafting-mu.")));
            killer.sendActionBar(color(msg("head-drop-actionbar", "&ePlayer Head didapatkan")));
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int lives = readLives(p.getUniqueId());
            Integer old = lifeSnapshot.put(p.getUniqueId(), lives);
            if (old != null && old >= 0 && lives >= 0 && lives < old) {
                boolean hadProgress = stages.remove(p.getUniqueId()) != null;
                clearEffects(p, hadProgress);
                saveData();
            } else {
                int stage = stages.getOrDefault(p.getUniqueId(), 0);
                if (stage > 0) applyStage(p, stage);
            }
        }, 2L);
    }

    private ItemStack makePlayerHead(org.bukkit.OfflinePlayer owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (owner != null) meta.setOwningPlayer(owner);
        mark(meta, "player_head");
        String nickname = owner == null || owner.getName() == null ? "Player" : owner.getName();
        meta.setDisplayName(color("&f" + nickname + " Head"));
        meta.setLore(lines("player-head-lore"));
        applyTexture(meta, "textures.player-head");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeStageItem(int stage, ItemStack sourceHead) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (sourceHead != null && sourceHead.getItemMeta() instanceof SkullMeta sourceMeta
                && sourceMeta.getOwningPlayer() != null) {
            meta.setOwningPlayer(sourceMeta.getOwningPlayer());
        }
        mark(meta, "stage_" + stage);
        meta.setDisplayName(color(msg("stage-" + stage + "-name", defaultStageName(stage))));
        meta.setLore(lines("stage-" + stage + "-lore"));
        // Enchantment glint aktif untuk semua item Stage 1, 2, dan 3.
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        applyTexture(meta, "textures.stage-" + stage);
        item.setItemMeta(meta);
        return item;
    }

    private void mark(ItemMeta meta, String id) {
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
    }

    private String itemId(ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || !item.hasItemMeta()) return "";
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(itemKey, PersistentDataType.STRING, "");
    }

    private String defaultStageName(int stage) {
        return switch (stage) {
            case 1 -> "&bSharp as a Diamond";
            case 2 -> "&bHard as a Diamond";
            default -> "&cAbsolute Unit";
        };
    }

    private void registerRecipes() {
        addRecipe(1);
        addRecipe(2);
        addRecipe(3);
    }

    private void addRecipe(int stage) {
        String base = "recipes.stage-" + stage + ".";
        List<String> rows = getConfig().getStringList(base + "shape");
        if (rows.size() != 3) rows = switch (stage) {
            case 1 -> List.of("DSD", "DHD", "DDD");
            case 2 -> List.of("DSD", "DHD", "DCD");
            default -> List.of("HNH", "SHC", "DDD");
        };
        NamespacedKey key = new NamespacedKey(this, "stage_" + stage);
        ShapedRecipe recipe = new ShapedRecipe(key, makeStageItem(stage, null));
        recipe.shape(rows.get(0), rows.get(1), rows.get(2));
        for (String symbol : List.of("D", "S", "C", "N", "H")) {
            String materialName = getConfig().getString(base + "ingredients." + symbol);
            if (materialName == null) materialName = switch (symbol) {
                case "D" -> "DIAMOND";
                case "S" -> "DIAMOND_SWORD";
                case "C" -> "DIAMOND_CHESTPLATE";
                case "N" -> "NETHERITE_INGOT";
                default -> "PLAYER_HEAD";
            };
            if (rows.stream().noneMatch(row -> row.contains(symbol))) continue;
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                getLogger().warning("Material recipe tidak valid: " + materialName + " (stage " + stage + ")");
                continue;
            }
            if (symbol.equals("H")) recipe.setIngredient('H', new RecipeChoice.MaterialChoice(Material.PLAYER_HEAD));
            else recipe.setIngredient(symbol.charAt(0), material);
        }
        Bukkit.removeRecipe(key);
        Bukkit.addRecipe(recipe);
    }

    private void applyTexture(SkullMeta meta, String path) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isBlank()) return;
        try {
            String url = raw.trim();
            if (url.startsWith("eyJ")) {
                String decoded = new String(Base64.getDecoder().decode(url), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)").matcher(decoded);
                if (matcher.find()) url = matcher.group(1);
            }
            if (!url.startsWith("https://textures.minecraft.net/texture/")) {
                getLogger().warning("Texture " + path + " harus URL textures.minecraft.net atau Base64 texture valid.");
                return;
            }
            var profile = Bukkit.createProfile(UUID.randomUUID());
            var textures = profile.getTextures();
            textures.setSkin(new URL(url));
            profile.setTextures(textures);
            meta.setPlayerProfile(profile);
        } catch (Exception ex) {
            getLogger().warning("Gagal memasang texture " + path + ": " + ex.getMessage());
        }
    }

    @EventHandler public void onPrepareCraft(PrepareItemCraftEvent e) {
        int stage = recipeStage(e.getRecipe());
        if (stage == 0) return;
        ItemStack[] matrix = e.getInventory().getMatrix();
        if (matrix.length < 5 || !itemId(matrix[4]).equals("player_head")) {
            e.getInventory().setResult(null);
            return;
        }
        if (e.getView().getPlayer() instanceof Player player) {
            int active = stages.getOrDefault(player.getUniqueId(), 0);
            if ((stage == 2 && active < 1) || (stage == 3 && active < 2)
                    || (stage == 1 && active >= 1) || (stage == 2 && active >= 2)
                    || (stage == 3 && active >= 3)) return;
        }
        e.getInventory().setResult(makeStageItem(stage, matrix[4]));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        int stage = recipeStage(e.getRecipe());
        if (stage == 0) return;
        if (!(e.getWhoClicked() instanceof Player p)) {
            e.setCancelled(true);
            return;
        }
        ItemStack[] matrix = e.getInventory().getMatrix();
        if (matrix.length < 5 || !itemId(matrix[4]).equals("player_head")) {
            e.setCancelled(true);
            return;
        }
        int active = stages.getOrDefault(p.getUniqueId(), 0);
        if (stage == 2 && active < 1) {
            e.setCancelled(true);
            p.sendMessage(color(msg("need-stage-1", "&cKamu harus mengaktifkan Sharp as a Diamond terlebih dahulu.")));
        } else if (stage == 3 && active < 2) {
            e.setCancelled(true);
            p.sendMessage(color(msg("need-stage-2", "&cKamu harus mengaktifkan Hard as a Diamond terlebih dahulu.")));
        } else if ((stage == 1 && active >= 1) || (stage == 2 && active >= 2) || (stage == 3 && active >= 3)) {
            e.setCancelled(true);
            p.sendMessage(color(msg("already-stage", "&eTahap ini sudah aktif atau progresmu lebih tinggi.")));
        }
    }

    private int recipeStage(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) return 0;
        return switch (keyed.getKey().getKey()) {
            case "stage_1" -> 1;
            case "stage_2" -> 2;
            case "stage_3" -> 3;
            default -> 0;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        String id = itemId(e.getItemInHand());
        if (!id.equals("player_head") && !id.startsWith("stage_")) return;

        int stage = 0;
        if (id.startsWith("stage_")) {
            try { stage = Integer.parseInt(id.substring(6)); }
            catch (NumberFormatException ex) { return; }
        }

        String key = locKey(e.getBlockPlaced().getLocation());
        getConfig().set("placed." + key, stage);

        // Simpan salinan item custom lengkap (PDC, nama, lore, tekstur, glint)
        // agar saat blok dihancurkan item yang jatuh bukan Player Head vanilla.
        ItemStack preserved = e.getItemInHand().clone();
        preserved.setAmount(1);
        getConfig().set("placed-items." + key, preserved);
        saveData();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !e.getPlayer().isSneaking() || e.getClickedBlock() == null)
            return;
        Block block = e.getClickedBlock();
        String key = locKey(block.getLocation());
        int stage = getConfig().getInt("placed." + key, 0);
        if (stage <= 0) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        int active = stages.getOrDefault(p.getUniqueId(), 0);
        if (stage > 1 && active < stage - 1) {
            p.sendMessage(color(stage == 2
                    ? msg("need-stage-1", "&cAktifkan Sharp as a Diamond terlebih dahulu.")
                    : msg("need-stage-2", "&cAktifkan Hard as a Diamond terlebih dahulu.")));
            return;
        }
        if (stage <= active) {
            p.sendMessage(color(msg("already-stage", "&eTahap ini sudah pernah kamu aktifkan.")));
            return;
        }
        if ((stage == 1 && active != 0) || (stage == 2 && active != 1) || (stage == 3 && active != 2))
            return;

        // Progress dan potion baru diberikan setelah animasi kepala selesai.
        lifeSnapshot.putIfAbsent(p.getUniqueId(), readLives(p.getUniqueId()));
        playActivationEffects(p, stage, block.getLocation().add(0.5, 0.2, 0.5));
        block.setType(Material.AIR);
        getConfig().set("placed." + key, null);
        getConfig().set("placed-items." + key, null);
        removeStageVisuals(block.getLocation());
        saveData();
        p.sendMessage(color(msg("activated-" + stage, "&aTahap " + stage + " berhasil diaktifkan!")));
        p.sendActionBar(color(msg("activated-actionbar", "&cBreak your limit &f| &aEfek aktif")));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String key = locKey(e.getBlock().getLocation());
        String dataPath = "placed." + key;
        if (!getConfig().contains(dataPath)) return;

        int stage = getConfig().getInt(dataPath, 0);
        ItemStack preserved = getConfig().getItemStack("placed-items." + key);

        // Jika serialisasi item gagal / PDC hilang, bangun ulang item custom dari
        // data stage dan pemilik skin pada block state agar tidak jatuh sebagai head vanilla.
        if (preserved == null || preserved.getType() != Material.PLAYER_HEAD
                || itemId(preserved).isEmpty()) {
            org.bukkit.OfflinePlayer owner = null;
            if (e.getBlock().getState() instanceof org.bukkit.block.Skull skullState) {
                owner = skullState.getOwningPlayer();
            }
            ItemStack source = new ItemStack(Material.PLAYER_HEAD);
            if (owner != null) {
                SkullMeta sourceMeta = (SkullMeta) source.getItemMeta();
                sourceMeta.setOwningPlayer(owner);
                source.setItemMeta(sourceMeta);
            }
            preserved = stage > 0 ? makeStageItem(stage, source) : makePlayerHead(owner);
        }

        // Matikan drop vanilla, lalu jatuhkan item custom lengkap dengan PDC/nama/lore/skin.
        e.setDropItems(false);
        e.getBlock().getWorld().dropItemNaturally(
                e.getBlock().getLocation().add(0.5, 0.2, 0.5), preserved.clone());

        getConfig().set(dataPath, null);
        getConfig().set("placed-items." + key, null);
        removeStageVisuals(e.getBlock().getLocation());
        saveData();
    }

    private void spawnPlacedHeadRedstone() {
        if (!getConfig().isConfigurationSection("placed")) return;

        // Partikel DUST merah murni visual: tidak membuat/mengubah blok redstone.
        Particle.DustOptions bloodDust = new Particle.DustOptions(Color.fromRGB(190, 15, 25), 1.25f);
        for (String key : getConfig().getConfigurationSection("placed").getKeys(false)) {
            String[] parts = key.split(",");
            if (parts.length != 4) continue;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) continue;

            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                // Kepala berada di (x,y,z); partikel menempel di permukaan lantai y-1,
                // membentuk bercak kecil di sekeliling pangkal kepala.
                double[][] drops = {
                        {0.18, 0.02, 0.18}, {0.36, 0.02, 0.12},
                        {0.70, 0.02, 0.20}, {0.82, 0.02, 0.42},
                        {0.76, 0.02, 0.72}, {0.55, 0.02, 0.82},
                        {0.25, 0.02, 0.76}, {0.12, 0.02, 0.52},
                        {0.48, 0.02, 0.48}
                };
                for (double[] drop : drops) {
                    world.spawnParticle(Particle.DUST,
                            new Location(world, x + drop[0], y + drop[1], z + drop[2]),
                            1, 0, 0, 0, 0, bloodDust);
                }
            } catch (NumberFormatException ignored) {
                getLogger().warning("Lokasi kepala tersimpan tidak valid: " + key);
            }
        }
    }


    // Visual panggung khusus untuk item Stage 1-3 yang dipasang di lantai.
    // Semua visual adalah entity/particle dekoratif; tidak mengubah block dunia.
    private String stageVisualTag(Location location) {
        String identity = locKey(location.getBlock().getLocation());
        return "kpk_stagefx_v5_" + UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private void removeStageVisuals(Location location) {
        if (location.getWorld() == null) return;
        String tag = stageVisualTag(location);
        String legacyTag = "kpk_stagefx_" + UUID.nameUUIDFromBytes(
                locKey(location.getBlock().getLocation()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        String identity = UUID.nameUUIDFromBytes(
                locKey(location.getBlock().getLocation()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
        for (ItemDisplay display : location.getWorld().getEntitiesByClass(ItemDisplay.class)) {
            Set<String> tags = display.getScoreboardTags();
            if (tags.contains(tag) || tags.contains(legacyTag)
                    || tags.contains("kpk_stagefx_v4_" + identity)
                    || tags.contains("kpk_stagefx_v4_" + identity + "_helmet")
                    || tags.contains("kpk_stagefx_v3_" + identity)
                    || tags.contains("kpk_stagefx_v3_" + identity + "_helmet")
                    || tags.contains("kpk_stagefx_v2_" + identity)
                    || tags.contains("kpk_stagefx_v2_" + identity + "_helmet"))
                display.remove();
        }
    }

    private void updateStageFloorVisuals() {
        if (!getConfig().isConfigurationSection("placed")) return;
        for (String key : getConfig().getConfigurationSection("placed").getKeys(false)) {
            int stage = getConfig().getInt("placed." + key, 0);
            if (stage < 1 || stage > 3) continue;
            String[] parts = key.split(",");
            if (parts.length != 4) continue;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) continue;
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                Location blockLoc = new Location(world, x, y, z);
                if (blockLoc.getBlock().getType() != Material.PLAYER_HEAD
                        && blockLoc.getBlock().getType() != Material.PLAYER_WALL_HEAD) {
                    removeStageVisuals(blockLoc);
                    continue;
                }
                String tag = stageVisualTag(blockLoc);
                String identity = UUID.nameUUIDFromBytes(locKey(blockLoc.getBlock().getLocation())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
                String previousTagV2 = "kpk_stagefx_v2_" + identity;
                String previousTagV3 = "kpk_stagefx_v3_" + identity;
                String previousTagV4 = "kpk_stagefx_v4_" + identity;
                for (ItemDisplay oldDisplay : world.getEntitiesByClass(ItemDisplay.class)) {
                    if (oldDisplay.getScoreboardTags().contains(previousTagV2)
                            || oldDisplay.getScoreboardTags().contains(previousTagV3)
                            || oldDisplay.getScoreboardTags().contains(previousTagV4)
                            || oldDisplay.getScoreboardTags().contains(previousTagV2 + "_helmet")
                            || oldDisplay.getScoreboardTags().contains(previousTagV3 + "_helmet")
                            || oldDisplay.getScoreboardTags().contains(previousTagV4 + "_helmet")) oldDisplay.remove();
                }
                boolean exists = world.getEntitiesByClass(ItemDisplay.class).stream()
                        .anyMatch(display -> display.getScoreboardTags().contains(tag));
                Location center = blockLoc.clone().add(0.5, 0.52, 0.5);

                if (stage == 2) {
                    // Aura Warden/sculk tetap menjadi efek utama di sekitar kepala.
                    Location aura = center.clone().add(0, -0.08, 0);
                    world.spawnParticle(Particle.SCULK_SOUL, aura, 3, 0.16, 0.12, 0.16, 0.008);
                    world.spawnParticle(Particle.SCULK_CHARGE, aura, 2, 0.13, 0.10, 0.13, 0.01, 0.0f);
                    world.spawnParticle(Particle.DUST, aura, 5, 0.17, 0.13, 0.17, 0,
                            new Particle.DustOptions(Color.fromRGB(35, 105, 125), 0.85f));
                    // Asap kecil sebagai aksen tambahan, jauh lebih halus daripada aura Warden.
                    world.spawnParticle(Particle.SMOKE, aura.clone().add(0, 0.02, 0), 2,
                            0.055, 0.045, 0.055, 0.002);
                }
                if (stage == 3) {
                    // Pendarahan merah sangat kecil di titik pedang menancap.
                    Location wound = center.clone().add(0, 0.28, 0);
                    world.spawnParticle(Particle.DUST, wound, 2, 0.035, 0.025, 0.035, 0,
                            new Particle.DustOptions(Color.fromRGB(175, 8, 18), 0.55f));
                    // Api dekoratif sangat kecil; redstone/darah tetap menjadi efek dominan.
                    Location ember = blockLoc.clone().add(0.5, 0.045, 0.5);
                    // Api kecil seperti flint and steel: beberapa lidah api dan asap tipis
                    // muncul di permukaan lantai di sekeliling pangkal kepala.
                    world.spawnParticle(Particle.FLAME, ember, 4, 0.12, 0.015, 0.12, 0.002);
                    world.spawnParticle(Particle.SMOKE, ember.clone().add(0, 0.025, 0), 2,
                            0.10, 0.025, 0.10, 0.001);
                    world.spawnParticle(Particle.LAVA, ember, 1, 0.08, 0.01, 0.08, 0);
                }

                if (exists || stage == 2) continue;
                Material sword = stage == 1 ? Material.DIAMOND_SWORD : Material.NETHERITE_SWORD;
                ItemDisplay display = world.spawn(center, ItemDisplay.class, entity -> {
                    entity.setItemStack(new ItemStack(sword));
                    entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    entity.setPersistent(true);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.addScoreboardTag(tag);
                    Quaternionf rotation = new Quaternionf();
                    if (stage == 1) {
                        // Pedang menancap diagonal 45 derajat ke bagian atas kepala.
                        rotation.rotateZ((float) Math.toRadians(-45));
                    } else {
                        // Stage 3: rotasi 180 derajat membuat bilah tegak mengarah lurus ke bawah,
                        // sementara gagang berada di atas kepala (bukan diagonal/rebah).
                        rotation.rotateZ((float) Math.toRadians(180));
                    }
                    entity.setTransformation(new Transformation(
                            new Vector3f(0f, 0f, 0f), rotation,
                            new Vector3f(stage == 3 ? 0.58f : 0.72f,
                                    stage == 3 ? 0.58f : 0.72f,
                                    stage == 3 ? 0.58f : 0.72f), new Quaternionf()));
                });
                if (stage == 3) {
                    // Pangkal pedang berada di atas kepala; ujung bilah mengarah masuk ke skull.
                    // Tempatkan pedang pada sumbu tengah: gagang terlihat di atas,
                    // sementara ujung bilah masuk ke bagian tengah atas Player Head.
                    display.teleport(center.clone().add(0, 0.02, 0));
                }
            } catch (NumberFormatException ignored) {
                getLogger().warning("Lokasi visual stage tidak valid: " + key);
            }
        }
    }

    // Empat ItemDisplay SHIELD membentuk halo yang mengorbit kepala player pada Stage 2.
    private void updateStage2ShieldHalos() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (stages.getOrDefault(id, 0) != 2) {
                removeStage2ShieldHalo(id);
                continue;
            }
            List<ItemDisplay> displays = stage2ShieldHalos.get(id);
            if (displays == null || displays.stream().anyMatch(d -> !d.isValid())) {
                removeStage2ShieldHalo(id);
                displays = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    ItemDisplay shield = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, entity -> {
                        entity.setItemStack(new ItemStack(Material.SHIELD));
                        entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        entity.setPersistent(false);
                        entity.setInvulnerable(true);
                        entity.setSilent(true);
                        entity.setTransformation(new Transformation(
                                new Vector3f(0f, 0f, 0f), new Quaternionf(),
                                new Vector3f(0.30f, 0.30f, 0.30f), new Quaternionf()));
                    });
                    displays.add(shield);
                }
                stage2ShieldHalos.put(id, displays);
            }
            // Pusat halo selalu mengikuti posisi kepala PLAYER aktif, bukan lokasi item
            // Stage 2 yang dipajang di lantai. EyeLocation juga mengikuti gerakan/lompatan.
            Location head = player.getEyeLocation().add(0, 0.18, 0);
            double phase = now * 0.00135;
            for (int i = 0; i < displays.size(); i++) {
                double angle = phase + (Math.PI * 2.0 * i / 4.0);
                Location orbit = head.clone().add(Math.cos(angle) * 0.62,
                        Math.sin(angle * 2.0) * 0.10, Math.sin(angle) * 0.62);
                ItemDisplay shield = displays.get(i);
                shield.teleport(orbit);
                shield.setRotation((float) Math.toDegrees(-angle) + 90.0f, 0.0f);
            }
        }
        for (UUID id : new HashSet<>(stage2ShieldHalos.keySet())) {
            if (Bukkit.getPlayer(id) == null) removeStage2ShieldHalo(id);
        }
    }

    private void removeStage2ShieldHalo(UUID id) {
        List<ItemDisplay> displays = stage2ShieldHalos.remove(id);
        if (displays != null) for (ItemDisplay display : displays)
            if (display.isValid()) display.remove();
    }

    private void showGlitchSubtitle(Player player) {
        // String yang tampil tetap persis "Limit Breaker"; glitch dibuat lewat flicker merah.
        String subtitle = color("&c&lLimit Breaker");
        player.sendTitle("", subtitle, 0, 65, 10);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendTitle("", color("&4&lLimit Breaker"), 0, 65, 10);
        }, 2L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendTitle("", subtitle, 0, 65, 10);
        }, 4L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendTitle("", color("&4&lLimit Breaker"), 0, 65, 10);
        }, 6L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendTitle("", subtitle, 0, 65, 10);
        }, 8L);
    }

    private boolean isCustomHead(ItemStack item) {
        String id = itemId(item);
        return id.equals("player_head") || id.startsWith("stage_");
    }

    private void applyStage(Player p, int stage) {
        if (stage <= 0) return;
        int strength = stage == 3 ? 1 : 0;
        int resistance = stage >= 2 ? (stage == 3 ? 1 : 0) : -1;
        maintainEffect(p, PotionEffectType.STRENGTH, strength);
        if (resistance >= 0) maintainEffect(p, PotionEffectType.RESISTANCE, resistance);
    }

    private void maintainEffect(Player p, PotionEffectType type, int amplifier) {
        PotionEffect current = p.getPotionEffect(type);
        // Potion vanilla yang levelnya lebih tinggi harus dibiarkan sampai durasinya habis.
        if (current != null && current.getAmplifier() > amplifier) return;
        int duration = Math.max(1, getConfig().getInt("effects.duration-ticks", Integer.MAX_VALUE));
        int refreshAt = getConfig().getInt("effects.refresh-below-ticks", 80);
        if (current == null || current.getAmplifier() < amplifier || current.getDuration() < refreshAt) {
            p.addPotionEffect(new PotionEffect(type, duration, amplifier, false, false, true), true);
        }
    }

    private void playActivationEffects(Player p, int stage, Location at) {
        World world = at.getWorld();
        world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.0f);

        Item headEntity = world.dropItem(at, makeStageItem(stage, null));
        headEntity.setPickupDelay(Integer.MAX_VALUE);
        headEntity.setGravity(false);
        headEntity.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

        if (stage == 3) {
            // Empat End Crystal kecil mengorbit badan, lalu konvergen ke tengah dada.
            List<ItemDisplay> crystals = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ItemDisplay crystal = world.spawn(p.getLocation(), ItemDisplay.class, display -> {
                    display.setItemStack(new ItemStack(Material.END_CRYSTAL));
                    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    display.setPersistent(false);
                    display.setInvulnerable(true);
                    display.setSilent(true);
                    display.setTransformation(new Transformation(
                            new Vector3f(0f, 0f, 0f), new Quaternionf(),
                            new Vector3f(0.50f, 0.50f, 0.50f), new Quaternionf()));
                });
                crystals.add(crystal);
            }

            final BukkitTask[] animation = new BukkitTask[1];
            animation[0] = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                private int ticks = 0;

                @Override public void run() {
                    if (!p.isOnline() || !p.isValid()) {
                        if (headEntity.isValid()) headEntity.remove();
                        for (ItemDisplay crystal : crystals) if (crystal.isValid()) crystal.remove();
                        animation[0].cancel();
                        return;
                    }

                    Location playerBase = p.getLocation();
                    if (ticks < 30 && headEntity.isValid()) {
                        Location risingHead = headEntity.getLocation().add(0, 0.065, 0);
                        headEntity.teleport(risingHead);
                        world.spawnParticle(Particle.GLOW, risingHead, 7, 0.20, 0.16, 0.20, 0.01);
                        world.spawnParticle(Particle.END_ROD, risingHead, 3, 0.13, 0.13, 0.13, 0.005);
                    }

                    Location chest = playerBase.clone().add(0, 1.18, 0);
                    double orbitPhase = ticks * 0.16;
                    for (int i = 0; i < crystals.size(); i++) {
                        ItemDisplay crystal = crystals.get(i);
                        if (!crystal.isValid()) continue;
                        double angle = orbitPhase + (Math.PI * 2.0 * i / 4.0);
                        Location orbit = playerBase.clone().add(
                                Math.cos(angle) * 1.05,
                                0.95 + Math.sin(ticks * 0.12 + i) * 0.35,
                                Math.sin(angle) * 1.05);
                        Location target;
                        if (ticks < 48) {
                            target = orbit;
                        } else {
                            double progress = Math.min(1.0, (ticks - 48) / 23.0);
                            double startAngle = (48 * 0.16) + (Math.PI * 2.0 * i / 4.0);
                            Location startOrbit = playerBase.clone().add(
                                    Math.cos(startAngle) * 1.05,
                                    0.95 + Math.sin(48 * 0.12 + i) * 0.35,
                                    Math.sin(startAngle) * 1.05);
                            target = startOrbit.clone().add(
                                    (chest.getX() - startOrbit.getX()) * progress,
                                    (chest.getY() - startOrbit.getY()) * progress,
                                    (chest.getZ() - startOrbit.getZ()) * progress);
                        }
                        crystal.teleport(target);
                        crystal.setRotation((float) (ticks * 12.0 + i * 90.0), 0.0f);
                        world.spawnParticle(Particle.END_ROD, target, 2, 0.025, 0.025, 0.025, 0.002);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, target, 1, 0.02, 0.02, 0.02, 0.001);
                    }

                    if (ticks == 72) {
                        for (ItemDisplay crystal : crystals) if (crystal.isValid()) crystal.remove();
                        if (headEntity.isValid()) headEntity.remove();
                        // Ledakan End Crystal + dentuman thunder, seluruhnya visual/audio tanpa block damage.
                        world.playSound(chest, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.72f);
                        world.playSound(chest, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.85f);
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, chest, 1, 0, 0, 0, 0);
                        world.spawnParticle(Particle.FLAME, chest, 48, 0.42, 0.62, 0.42, 0.075);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, chest, 28, 0.36, 0.52, 0.36, 0.045);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, chest, 55, 0.48, 0.62, 0.48, 0.12);
                        world.spawnParticle(Particle.DUST, chest, 35, 0.42, 0.50, 0.42, 0.02,
                                new Particle.DustOptions(Color.fromRGB(210, 20, 35), 1.35f));
                    }

                    if (ticks >= 80) {
                        animation[0].cancel();
                        if (p.isOnline()) {
                            // Buff final baru diterapkan setelah crystal menyatu dan ledakan selesai.
                            stages.put(p.getUniqueId(), 3);
                            applyStage(p, 3);
                            saveData();
                            showGlitchSubtitle(p);
                            p.sendActionBar(color(msg("activated-actionbar", "&cBreak your limit &f| &aEfek aktif")));
                            sendGuide(p);
                        }
                        return;
                    }
                    ticks++;
                }
            }, 0L, 1L);
            return;
        }

        // Stage 1 dan 2 mempertahankan animasi melayang yang sudah teruji.
        final BukkitTask[] animation = new BukkitTask[1];
        animation[0] = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int ticks = 0;
            @Override public void run() {
                if (!headEntity.isValid() || ticks >= 30) {
                    if (headEntity.isValid()) {
                        Location burst = headEntity.getLocation();
                        world.playSound(burst, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, 0.9f);
                        world.playSound(burst, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        world.spawnParticle(Particle.FIREWORK, burst, 55, 0.45, 0.5, 0.45, 0.08);
                        world.spawnParticle(Particle.DUST, burst, 45, 0.5, 0.5, 0.5, 0.02,
                                new Particle.DustOptions(Color.PURPLE, 1.7f));
                        world.spawnParticle(Particle.PORTAL, burst, 35, 0.4, 0.5, 0.4, 0.25);
                        headEntity.remove();
                    }
                    animation[0].cancel();
                    if (p.isOnline()) {
                        stages.put(p.getUniqueId(), stage);
                        applyStage(p, stage);
                        saveData();
                        p.sendActionBar(color(msg("activated-actionbar", "&cBreak your limit &f| &aEfek aktif")));
                        sendGuide(p);
                    }
                    return;
                }
                Location rising = headEntity.getLocation().add(0, 0.065, 0);
                headEntity.teleport(rising);
                world.spawnParticle(Particle.GLOW, rising, 8, 0.22, 0.18, 0.22, 0.01);
                world.spawnParticle(Particle.END_ROD, rising, 3, 0.16, 0.16, 0.16, 0.005);
                ticks++;
            }
        }, 0L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventHeadEquip(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        boolean headCursor = isCustomHead(cursor);
        boolean headCurrent = isCustomHead(current);
        // Blokir hanya interaksi yang benar-benar menuju slot armor kepala.
        // Shift-click di chest/container harus tetap bisa memindahkan item custom.
        boolean armorSlot = e.getSlotType() == InventoryType.SlotType.ARMOR;
        boolean autoEquipFromPlayerInventory = e.isShiftClick()
                && e.getClickedInventory() instanceof PlayerInventory
                && e.getView().getTopInventory().getType() == InventoryType.CRAFTING
                && headCurrent;
        if ((armorSlot && (headCursor || headCurrent)) || autoEquipFromPlayerInventory) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(color(msg("head-equip-blocked", "&cPlayer Head tidak bisa dipakai sebagai helmet.")));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventHeadDrag(InventoryDragEvent e) {
        if (!isCustomHead(e.getOldCursor())) return;
        boolean targetsArmor = e.getRawSlots().stream()
                .anyMatch(rawSlot -> e.getView().getSlotType(rawSlot) == InventoryType.SlotType.ARMOR);
        if (targetsArmor) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(color(msg("head-equip-blocked", "&cPlayer Head tidak bisa dipakai sebagai helmet.")));
        }
    }

    private void openRecipeMenu(Player p) {
        RecipeHolder holder = new RecipeHolder(0);
        Inventory inv = Bukkit.createInventory(holder, 27, "§8KASTARA • Progression 1 → 2 → 3");
        holder.inventory = inv;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        int active = stages.getOrDefault(p.getUniqueId(), 0);
        inv.setItem(4, recipeInfo(Material.WRITABLE_BOOK, "&6&lBREAK YOUR LIMIT",
                "&7Selesaikan setiap tahap secara berurutan.",
                "&f1. Sharp as a Diamond",
                "&f2. Hard as a Diamond",
                "&f3. Absolute Unit",
                "&8Klik ikon tahap untuk melihat resep."));
        int[] slots = {10, 13, 16};
        for (int stage = 1; stage <= 3; stage++) {
            ItemStack icon = makeStageItem(stage, null);
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(" ");
            lore.add(color(stage <= active ? "&a✔ SUDAH DIAKTIFKAN"
                    : stage == active + 1 ? "&e▶ TAHAP BERIKUTNYA"
                    : "&c🔒 TERKUNCI"));
            if (stage == 1) lore.add(color("&7Syarat: Player Head"));
            if (stage == 2) lore.add(color("&7Syarat: Stage 1 aktif + Player Head"));
            if (stage == 3) lore.add(color("&7Syarat: Stage 1 & 2 aktif + Player Head"));
            lore.add(color("&eKlik untuk melihat resep"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slots[stage - 1], icon);
        }
        inv.setItem(11, named(Material.ARROW, "&eTahap 1 → 2"));
        inv.setItem(14, named(Material.ARROW, "&eTahap 2 → 3"));
        inv.setItem(22, recipeInfo(Material.PAPER, "&e&lCARA PROGRES",
                "&71. Craft item tahap pertama.",
                "&72. Letakkan item di lantai.",
                "&73. Lihat item lalu Shift + Klik Kanan.",
                "&74. Ulangi untuk tahap berikutnya.",
                "&cProgres reset jika mati / kehilangan life."));
        p.openInventory(inv);
    }

    private ItemStack recipeInfo(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) lore.add(color(line));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openRecipeDetail(Player p, int stage) {
        RecipeHolder holder = new RecipeHolder(stage);
        Inventory inv = Bukkit.createInventory(holder, 45, "§8Recipe • " + defaultStageName(stage));
        holder.inventory = inv;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        int active = stages.getOrDefault(p.getUniqueId(), 0);
        String stageLabel = switch (stage) {
            case 1 -> "TAHAP 1 / 3 • MULAI DI SINI";
            case 2 -> "TAHAP 2 / 3 • BUTUH TAHAP 1";
            default -> "TAHAP 3 / 3 • TAHAP FINAL";
        };
        inv.setItem(4, recipeInfo(Material.BOOK, "&6&l" + stageLabel,
                stage == 1 ? "&7Bisa dibuat dari awal dengan Player Head."
                        : stage == 2 ? "&7Aktifkan Sharp as a Diamond sebelum crafting."
                        : "&7Aktifkan Hard as a Diamond sebelum crafting.",
                "&7Urutan aktivasi: 1 → 2 → 3",
                "&cMati / kehilangan life akan mereset progres."));
        String base = "recipes.stage-" + stage + ".";
        List<String> rows = getConfig().getStringList(base + "shape");
        if (rows.size() != 3) rows = switch (stage) {
            case 1 -> List.of("DSD", "DHD", "DDD");
            case 2 -> List.of("DSD", "DHD", "DCD");
            default -> List.of("HNH", "SHC", "DDD");
        };
        Map<Character, Material> mats = new HashMap<>();
        for (char c : new char[]{'D','S','C','N','H'}) {
            String fallback = switch (c) { case 'D' -> "DIAMOND"; case 'S' -> "DIAMOND_SWORD";
                case 'C' -> "DIAMOND_CHESTPLATE"; case 'N' -> "NETHERITE_INGOT"; default -> "PLAYER_HEAD"; };
            Material m = Material.matchMaterial(getConfig().getString(base + "ingredients." + c, fallback));
            mats.put(c, m == null ? Material.BARRIER : m);
        }
        int[] slots = {10,11,12,19,20,21,28,29,30};
        for (int r=0;r<3;r++) for (int c=0;c<3;c++) {
            char symbol = rows.get(r).charAt(c);
            if (symbol == ' ') continue;
            ItemStack ingredient = new ItemStack(mats.getOrDefault(symbol, Material.BARRIER));
            ItemMeta meta = ingredient.getItemMeta();
            if (symbol == 'H') meta.setDisplayName(color("&fPlayer Head &7(custom item)"));
            else meta.setDisplayName(color("&f" + mats.get(symbol).name()));
            ingredient.setItemMeta(meta);
            inv.setItem(slots[r*3+c], ingredient);
        }
        inv.setItem(23, named(Material.ARROW, "&eHASIL CRAFTING"));
        ItemStack output = makeStageItem(stage, null);
        ItemMeta outputMeta = output.getItemMeta();
        List<String> outputLore = outputMeta.hasLore() ? new ArrayList<>(outputMeta.getLore()) : new ArrayList<>();
        outputLore.add(" ");
        outputLore.add(color(stage <= active ? "&aStatus: sudah diaktifkan"
                : stage == active + 1 ? "&eStatus: tahap berikutnya"
                : "&cStatus: terkunci — selesaikan tahap sebelumnya"));
        outputLore.add(color("&7Setelah craft: taruh di lantai, lihat, Shift + Klik Kanan."));
        outputMeta.setLore(outputLore);
        output.setItemMeta(outputMeta);
        inv.setItem(25, output);
        inv.setItem(40, named(Material.ARROW, "&eKembali ke urutan tahap"));
        inv.setItem(44, named(Material.BARRIER, "&cTutup"));
        p.openInventory(inv);
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta(); meta.setDisplayName(color(name)); item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onRecipeMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getInventory().getHolder() instanceof RecipeHolder holder)) return;
        e.setCancelled(true);
        if (holder.stage == 0) {
            int slot = e.getRawSlot();
            if (slot == 11) openRecipeDetail(p, 1);
            else if (slot == 13) openRecipeDetail(p, 2);
            else if (slot == 15) openRecipeDetail(p, 3);
        } else if (e.getRawSlot() == 40) openRecipeMenu(p);
        else if (e.getRawSlot() == 44) p.closeInventory();
    }

    private static final class RecipeHolder implements InventoryHolder {
        private final int stage;
        private Inventory inventory;
        RecipeHolder(int stage) { this.stage = stage; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private void sendGuide(Player p) {
        int stage = stages.getOrDefault(p.getUniqueId(), 0);
        String next = stage == 0 ? "Sharp as a Diamond"
                : stage == 1 ? "Hard as a Diamond"
                : stage == 2 ? "Absolute Unit" : "Tahap final sudah aktif";
        p.sendMessage(color(msg("guide-header", "&c&lBreak your limit")));
        p.sendMessage(color(msg("guide-next", "&eTahap berikutnya: &f{stage}").replace("{stage}", next)));
        p.sendMessage(color(msg("guide-use", "&7Craft item, letakkan kepala di tanah dengan klik kanan, lalu lihat kepala dan tekan &fShift + Klik Kanan&7.")));
        p.sendMessage(color(msg("guide-warning", "&cPeringatan: efek dan progres hilang jika kamu mati atau kehilangan life.")));
        p.sendActionBar(color(msg("guide-actionbar", "&eBreak your limit &7• Tahap berikutnya: &f{stage}").replace("{stage}", next)));
    }

    private List<String> lines(String path) {
        List<String> list = getConfig().getStringList(path);
        if (list.isEmpty()) list = List.of(
                "&7Drop dari membunuh player lain.",
                "&e/recipe untuk upgrade",
                "&fShift + Klik Kanan untuk menggunakan.");
        List<String> out = new ArrayList<>();
        for (String line : list) out.add(color(line));
        return out;
    }

    private String msg(String path, String fallback) {
        return getConfig().getString("messages." + path, fallback);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private String locKey(Location location) {
        return location.getWorld().getName() + "," + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        java.io.File file = new java.io.File(getDataFolder(), "data.yml");
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (Exception ex) { getLogger().warning("Tidak bisa membuat data.yml: " + ex.getMessage()); }
        }
        org.bukkit.configuration.file.YamlConfiguration data =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        if (data.getConfigurationSection("stages") != null) {
            for (String key : data.getConfigurationSection("stages").getKeys(false)) {
                try { stages.put(UUID.fromString(key), data.getInt("stages." + key)); }
                catch (Exception ignored) { }
            }
        }
        for (String key : data.getStringList("first-guides")) {
            try { firstHeadGuideSent.add(UUID.fromString(key)); }
            catch (Exception ignored) { }
        }
        if (data.getConfigurationSection("life-snapshot") != null) {
            for (String key : data.getConfigurationSection("life-snapshot").getKeys(false)) {
                try { lifeSnapshot.put(UUID.fromString(key), data.getInt("life-snapshot." + key)); }
                catch (Exception ignored) { }
            }
        }
        if (data.getConfigurationSection("placed") != null) {
            for (String key : data.getConfigurationSection("placed").getKeys(false))
                getConfig().set("placed." + key, data.getInt("placed." + key));
        }
        if (data.getConfigurationSection("placed-items") != null) {
            for (String key : data.getConfigurationSection("placed-items").getKeys(false)) {
                ItemStack saved = data.getItemStack("placed-items." + key);
                if (saved != null) getConfig().set("placed-items." + key, saved);
            }
        }
        saveConfig();
    }

    private void saveData() {
        org.bukkit.configuration.file.YamlConfiguration data = new org.bukkit.configuration.file.YamlConfiguration();
        for (var entry : stages.entrySet()) data.set("stages." + entry.getKey(), entry.getValue());
        data.set("first-guides", firstHeadGuideSent.stream().map(UUID::toString).toList());
        for (var entry : lifeSnapshot.entrySet())
            data.set("life-snapshot." + entry.getKey(), entry.getValue());
        if (getConfig().isConfigurationSection("placed"))
            data.set("placed", getConfig().getConfigurationSection("placed").getValues(false));
        if (getConfig().isConfigurationSection("placed-items"))
            data.set("placed-items", getConfig().getConfigurationSection("placed-items").getValues(false));
        try { data.save(new java.io.File(getDataFolder(), "data.yml")); }
        catch (Exception ex) { getLogger().warning("Gagal menyimpan data: " + ex.getMessage()); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recipe")) {
            if (sender instanceof Player p) openRecipeMenu(p);
            else sender.sendMessage("Command ini hanya bisa digunakan oleh player.");
            return true;
        }
        if (!sender.hasPermission("kastaraplayerkiller.admin")) {
            sender.sendMessage(color("&cTidak punya izin."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(color("&aConfig direload."));
            return true;
        }
        sender.sendMessage(color("&e/playerkiller reload"));
        return true;
    }
}
