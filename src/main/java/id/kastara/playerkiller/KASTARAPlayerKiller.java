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
        getLogger().info("KASTARA-PlayerKiller enabled; hooked into KASTARALives.");
    }

    @Override public void onDisable() {
        if (pollTask != null) pollTask.cancel();
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
            p.sendMessage(color(msg("reset-message", "&cNyawa berkurang! Semua efek dan progres Player/Killer telah di-reset.")));
            p.sendActionBar(color(msg("reset-actionbar", "&cProgres Player/Killer di-reset")));
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

    private ItemStack makePlayerHead(Player owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        mark(meta, "player_head");
        meta.setDisplayName(color("&fPlayer Head"));
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
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
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
            default -> "&cPlayer/Killer";
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
        if (!id.startsWith("stage_")) return;
        int stage;
        try { stage = Integer.parseInt(id.substring(6)); }
        catch (NumberFormatException ex) { return; }
        getConfig().set("placed." + locKey(e.getBlockPlaced().getLocation()), stage);
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

        applyStage(p, stage);

        stages.put(p.getUniqueId(), stage);
        lifeSnapshot.putIfAbsent(p.getUniqueId(), readLives(p.getUniqueId()));
        playActivationEffects(p, stage, block.getLocation().add(0.5, 0.2, 0.5));
        block.setType(Material.AIR);
        getConfig().set("placed." + key, null);
        saveData();
        p.sendMessage(color(msg("activated-" + stage, "&aTahap " + stage + " berhasil diaktifkan!")));
        if (stage == 3) p.sendTitle(
                color(getConfig().getString("final-title.title", "&d&lare you a player or a killer??")),
                color(getConfig().getString("final-title.subtitle", "&7")),
                10, 60, 20);
        p.sendActionBar(color(msg("activated-actionbar", "&bPlayer/Killer &f| &aEfek aktif")));
        sendGuide(p);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        String key = locKey(e.getBlock().getLocation());
        if (getConfig().contains("placed." + key)) {
            getConfig().set("placed." + key, null);
            saveData();
        }
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
        // Item head melayang perlahan hingga sekitar 2 blok, lalu pecah dengan burst ungu.
        Item entity = at.getWorld().dropItem(at, makeStageItem(stage, null));
        entity.setPickupDelay(Integer.MAX_VALUE);
        entity.setGravity(false);
        entity.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        final BukkitTask[] animation = new BukkitTask[1];
        animation[0] = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int ticks = 0;
            @Override public void run() {
                if (!entity.isValid() || ticks >= 30) {
                    if (entity.isValid()) {
                        Location burst = entity.getLocation();
                        burst.getWorld().spawnParticle(Particle.FIREWORK, burst, 55, 0.45, 0.5, 0.45, 0.08);
                        burst.getWorld().spawnParticle(Particle.DUST, burst, 45, 0.5, 0.5, 0.5, 0.02,
                                new Particle.DustOptions(Color.PURPLE, 1.7f));
                        burst.getWorld().spawnParticle(Particle.PORTAL, burst, 35, 0.4, 0.5, 0.4, 0.25);
                        entity.remove();
                    }
                    animation[0].cancel();
                    return;
                }
                entity.teleport(entity.getLocation().add(0, 0.065, 0));
                ticks++;
            }
        }, 0L, 1L);
        try {
            Sound sound = Sound.valueOf(getConfig().getString("effects.activation-sound",
                    "ITEM_NETHERITE_UPGRADE_SMITHING_TEMPLATE_USE"));
            p.playSound(at, sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ex) {
            p.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventHeadEquip(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        boolean headCursor = itemId(cursor).equals("player_head");
        boolean headCurrent = itemId(current).equals("player_head");
        // SlotType.ARMOR berlaku pada survival, creative, dan layout inventory lainnya.
        boolean armorSlot = e.getSlotType() == InventoryType.SlotType.ARMOR;
        if ((armorSlot && headCursor) || (armorSlot && headCurrent) || (e.isShiftClick() && headCurrent)) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(color(msg("head-equip-blocked", "&cPlayer Head tidak bisa dipakai sebagai helmet.")));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preventHeadDrag(InventoryDragEvent e) {
        if (!itemId(e.getOldCursor()).equals("player_head")) return;
        boolean targetsArmor = e.getRawSlots().stream()
                .anyMatch(rawSlot -> e.getView().getSlotType(rawSlot) == InventoryType.SlotType.ARMOR);
        if (targetsArmor) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage(color(msg("head-equip-blocked", "&cPlayer Head tidak bisa dipakai sebagai helmet.")));
        }
    }

    private void openRecipeMenu(Player p) {
        RecipeHolder holder = new RecipeHolder(0);
        Inventory inv = Bukkit.createInventory(holder, 27, RECIPE_TITLE);
        holder.inventory = inv;
        int[] slots = {11, 13, 15};
        for (int stage = 1; stage <= 3; stage++) {
            ItemStack icon = makeStageItem(stage, null);
            inv.setItem(slots[stage - 1], icon);
        }
        p.openInventory(inv);
    }

    private void openRecipeDetail(Player p, int stage) {
        RecipeHolder holder = new RecipeHolder(stage);
        Inventory inv = Bukkit.createInventory(holder, 45, "§8Recipe • " + defaultStageName(stage));
        holder.inventory = inv;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
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
        inv.setItem(23, named(Material.ARROW, "&eMenghasilkan"));
        inv.setItem(25, makeStageItem(stage, null));
        inv.setItem(40, named(Material.ARROW, "&eKembali"));
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
                : stage == 2 ? "Player/Killer" : "Tahap final sudah aktif";
        p.sendMessage(color(msg("guide-header", "&6&lPLAYER/KILLER")));
        p.sendMessage(color(msg("guide-next", "&eTahap berikutnya: &f{stage}").replace("{stage}", next)));
        p.sendMessage(color(msg("guide-use", "&7Craft item, letakkan kepala di tanah dengan klik kanan, lalu lihat kepala dan tekan &fShift + Klik Kanan&7.")));
        p.sendMessage(color(msg("guide-warning", "&cPeringatan: efek dan progres hilang jika kamu mati atau kehilangan life.")));
        p.sendActionBar(color(msg("guide-actionbar", "&eTahap berikutnya: &f{stage}").replace("{stage}", next)));
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
