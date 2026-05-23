package com.vivify.kitshop;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class KitShopPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

  private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,##0.##");
  private static final String SHOP_TITLE = ChatColor.DARK_PURPLE + "Kit Shop";
  private static final String ADMIN_TITLE = ChatColor.DARK_PURPLE + "Kit Shop Editor";
  private static final int INVENTORY_SIZE = 54;
  private static final int PREVIOUS_SLOT = 45;
  private static final int INFO_SLOT = 49;
  private static final int NEXT_SLOT = 53;

  private final List<KitListing> listings = new ArrayList<>();
  private final Map<UUID, ItemStack> pendingAdds = new ConcurrentHashMap<>();
  private final Map<UUID, LifeStealProfile> lifeStealProfiles = new ConcurrentHashMap<>();
  private File kitsFile;
  private File lifeStealFile;
  private Economy economy;
  private int itemsPerPage;
  private BukkitTask pingOptimizerTask;
  private NamespacedKey lifeHeartKey;
  private NamespacedKey reviveBeaconKey;
  private NamespacedKey soulPlayerKey;
  private NamespacedKey soulNameKey;
  private NamespacedKey heartRecipeKey;
  private NamespacedKey reviveRecipeKey;

  @Override
  public void onEnable() {
    initializeLifeStealKeys();
    saveDefaultConfig();
    upgradeStockMessages();
    upgradeAccessMessages();
    upgradeMoneyMessages();
    upgradeCoreMessages();
    upgradePingOptimizerSettings();
    upgradeLifeStealSettings();
    itemsPerPage = Math.min(45, Math.max(9, getConfig().getInt("settings.items-per-page", 45)));
    kitsFile = new File(getDataFolder(), "kits.yml");
    lifeStealFile = new File(getDataFolder(), "lifesteal.yml");

    migrateLegacyKitShopData();
    setupEconomy();
    loadListings();
    loadLifeStealData();

    getServer().getPluginManager().registerEvents(this, this);
    registerCommand("kitshop");
    registerCommand("money");
    registerCommand("minelifecore");
    registerCommand("ping");
    registerCommand("server");
    registerCommand("withdraw");
    registerCommand("revive");
    registerCommand("lifesteal");
    registerLifeStealRecipes();
    applyLifeStealToOnlinePlayers();
    startPingOptimizer();
  }

  @Override
  public void onDisable() {
    stopPingOptimizer();
    removeLifeStealRecipes();
    saveListings();
    saveLifeStealData();
  }

  @Override
  public boolean onCommand(
      final CommandSender sender,
      final Command command,
      final String label,
      final String[] args) {
    if (matchesCommand(command, label, "server")) {
      handleServerCommand(sender, args);
      return true;
    }
    if (matchesCommand(command, label, "lifesteal", "lscore")) {
      handleLifeStealCommand(sender, args);
      return true;
    }

    if (!(sender instanceof Player player)) {
      sender.sendMessage(color(message("players-only")));
      return true;
    }

    if (matchesCommand(command, label, "money")) {
      handleMoneyCommand(player, args);
      return true;
    }
    if (matchesCommand(command, label, "ping", "mlping")) {
      handlePingCommand(player, args);
      return true;
    }
    if (matchesCommand(command, label, "minelifecore", "mlcore", "minecore")) {
      handleCoreCommand(player, label, args);
      return true;
    }
    if (matchesCommand(command, label, "withdraw", "withdrawheart")) {
      handleWithdrawCommand(player, args);
      return true;
    }
    if (matchesCommand(command, label, "revive")) {
      handleReviveCommand(player, args);
      return true;
    }
    if (!matchesCommand(command, label, "kitshop", "kshop")) {
      return false;
    }

    if (args.length == 0) {
      openBuyerShop(player, 0);
      return true;
    }

    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "buy" -> {
        openBuyerShop(player, 0);
        return true;
      }
      case "edit", "admin" -> {
        if (!isOperator(player)) {
          player.sendMessage(prefixed("no-permission"));
          return true;
        }
        openAdminShop(player, 0);
        return true;
      }
      case "add" -> {
        if (!isOperator(player)) {
          player.sendMessage(prefixed("no-permission"));
          return true;
        }
        if (args.length < 3) {
          player.sendMessage(color("&eUsage: /" + label + " add <price> <stock>"));
          return true;
        }
        Double price = parsePrice(args[1]);
        if (price == null) {
          player.sendMessage(prefixed("invalid-price"));
          return true;
        }
        Integer stock = parseStock(args[2]);
        if (stock == null) {
          player.sendMessage(prefixed("invalid-stock"));
          return true;
        }
        addHeldItem(player, price, stock);
        return true;
      }
      case "reload" -> {
        if (!isOperator(player)) {
          player.sendMessage(prefixed("no-permission"));
          return true;
        }
        reloadConfig();
        itemsPerPage = Math.min(45, Math.max(9, getConfig().getInt("settings.items-per-page", 45)));
        setupEconomy();
        loadListings();
        player.sendMessage(prefixed("reloaded"));
        return true;
      }
      case "help" -> {
        sendHelp(player, label);
        return true;
      }
      default -> {
        sendHelp(player, label);
        return true;
      }
    }
  }

  @Override
  public List<String> onTabComplete(
      final CommandSender sender,
      final Command command,
      final String alias,
      final String[] args) {
    if (matchesCommand(command, alias, "minelifecore", "mlcore", "minecore")) {
      if (args.length == 1) {
        List<String> completions = new ArrayList<>();
        completions.add("status");
        completions.add("help");
        if (sender.isOp() || sender.hasPermission("minelifecore.admin")) {
          completions.add("reload");
        }
        if (canStopServer(sender)) {
          completions.add("stop");
        }
        return completions.stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "ping", "mlping")) {
      if (args.length == 1 && sender.isOp()) {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "server")) {
      if (args.length == 1 && canStopServer(sender)) {
        return List.of("stop").stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "withdraw", "withdrawheart")) {
      if (args.length == 1) {
        return List.of("1", "2", "5", "10").stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "revive")) {
      if (args.length == 1) {
        return lifeStealProfiles.values().stream()
            .filter(LifeStealProfile::eliminated)
            .map(LifeStealProfile::name)
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "lifesteal", "lscore")) {
      if (args.length == 1) {
        List<String> completions = new ArrayList<>();
        completions.add("status");
        completions.add("help");
        if (canAdminLifeSteal(sender)) {
          completions.add("set");
          completions.add("revive");
          completions.add("unban");
          completions.add("reload");
        }
        return completions.stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      if (args.length == 2 && canAdminLifeSteal(sender)
          && ("set".equalsIgnoreCase(args[0]) || "status".equalsIgnoreCase(args[0]))) {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
            .toList();
      }
      if (args.length == 2 && canAdminLifeSteal(sender)
          && ("revive".equalsIgnoreCase(args[0]) || "unban".equalsIgnoreCase(args[0]))) {
        return lifeStealProfiles.values().stream()
            .filter(LifeStealProfile::eliminated)
            .map(profile -> sanitizePlayerName(profile.name()))
            .filter(value -> !value.isBlank())
            .distinct()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
            .toList();
      }
      if (args.length == 3 && canAdminLifeSteal(sender) && "set".equalsIgnoreCase(args[0])) {
        return List.of("1", "5", "10", "15", "20").stream()
            .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if (matchesCommand(command, alias, "money")) {
      if (!sender.isOp()) {
        return List.of();
      }
      if (args.length == 1) {
        List<String> completions = new ArrayList<>();
        completions.add("add");
        completions.add("remove");
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
          completions.add(onlinePlayer.getName());
        }
        return completions.stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      if (args.length == 2 && ("add".equalsIgnoreCase(args[0]) || "remove".equalsIgnoreCase(args[0]))) {
        return List.of("100", "1000", "1000000");
      }
      if (args.length == 2) {
        return List.of("add", "remove").stream()
            .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
            .toList();
      }
      if (args.length == 3 && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
        return List.of("100", "1000", "1000000");
      }
      return List.of();
    }

    if (!matchesCommand(command, alias, "kitshop", "kshop")) {
      return List.of();
    }

    if (args.length == 1) {
      List<String> completions = new ArrayList<>();
      completions.add("buy");
      completions.add("help");
      if (sender.isOp()) {
        completions.add("edit");
        completions.add("add");
        completions.add("reload");
      }
      return completions.stream()
          .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
          .toList();
    }
    if (args.length == 2 && "add".equalsIgnoreCase(args[0])) {
      return List.of("100", "500", "1000");
    }
    if (args.length == 3 && "add".equalsIgnoreCase(args[0])) {
      return List.of("1", "5", "10", "25");
    }
    return List.of();
  }

  @EventHandler
  public void onPlayerCommand(final PlayerCommandPreprocessEvent event) {
    String commandText = event.getMessage().trim();
    String lowerCommand = commandText.toLowerCase(Locale.ROOT);
    if (lowerCommand.equals("/money") || lowerCommand.startsWith("/money ")) {
      event.setCancelled(true);
      String[] parts = commandText.substring(1).trim().split("\\s+");
      String[] args = parts.length > 1 ? List.of(parts).subList(1, parts.length).toArray(String[]::new) : new String[0];
      handleMoneyCommand(event.getPlayer(), args);
    } else if (lowerCommand.equals("/ping") || lowerCommand.startsWith("/ping ")) {
      event.setCancelled(true);
      String[] parts = commandText.substring(1).trim().split("\\s+");
      String[] args = parts.length > 1 ? List.of(parts).subList(1, parts.length).toArray(String[]::new) : new String[0];
      handlePingCommand(event.getPlayer(), args);
    } else if (lowerCommand.equals("/server stop")) {
      event.setCancelled(true);
      handleServerCommand(event.getPlayer(), new String[] {"stop"});
    }
  }

  @EventHandler
  public void onInventoryClick(final InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (!(event.getView().getTopInventory().getHolder() instanceof KitInventoryHolder holder)) {
      return;
    }

    event.setCancelled(true);
    if (holder.mode() == ShopMode.ADMIN && !isOperator(player)) {
      player.closeInventory();
      player.sendMessage(prefixed("no-permission"));
      return;
    }

    int rawSlot = event.getRawSlot();
    if (rawSlot < 0) {
      return;
    }

    if (rawSlot >= event.getView().getTopInventory().getSize()) {
      if (holder.mode() == ShopMode.ADMIN) {
        ItemStack clicked = event.getCurrentItem();
        if (isRealItem(clicked)) {
          if (!isShulkerKit(clicked)) {
            player.sendMessage(prefixed("not-shulker"));
            return;
          }
          promptForPrice(player, clicked.clone());
        }
      }
      return;
    }

    if (rawSlot == PREVIOUS_SLOT && holder.page() > 0) {
      openShop(player, holder.mode(), holder.page() - 1);
      return;
    }

    if (rawSlot == NEXT_SLOT && hasNextPage(holder.page())) {
      openShop(player, holder.mode(), holder.page() + 1);
      return;
    }

    int listingIndex = holder.page() * itemsPerPage + rawSlot;
    if (rawSlot >= itemsPerPage || listingIndex >= listings.size()) {
      return;
    }

    if (holder.mode() == ShopMode.ADMIN) {
      removeListing(player, listingIndex);
      openAdminShop(player, Math.min(holder.page(), maxPage()));
    } else {
      buyListing(player, listingIndex, holder.page());
    }
  }

  @EventHandler
  public void onInventoryDrag(final InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof KitInventoryHolder) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onInventoryClose(final InventoryCloseEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof KitInventoryHolder) {
      event.getInventory().clear();
    }
  }

  @EventHandler
  public void onPlayerChat(final AsyncPlayerChatEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    ItemStack pendingItem = pendingAdds.get(playerId);
    if (pendingItem == null) {
      return;
    }

    event.setCancelled(true);
    String chatMessage = event.getMessage().trim();
    Bukkit.getScheduler().runTask(this, () -> handlePendingPrice(event.getPlayer(), playerId, chatMessage));
  }

  @EventHandler
  public void onPlayerDeath(final PlayerDeathEvent event) {
    handleLifeStealDeath(event);
  }

  @EventHandler
  public void onPlayerRespawn(final PlayerRespawnEvent event) {
    if (!isLifeStealEnabled()) {
      return;
    }
    LifeStealProfile profile = initializeLifeStealProfile(event.getPlayer());
    if (!profile.hasBedSpawn() && !profile.eliminated()) {
      event.setRespawnLocation(defaultLifeStealRespawnLocation());
    }
    Bukkit.getScheduler().runTaskLater(this, () -> applyLifeStealHealth(event.getPlayer()), 1L);
  }

  @EventHandler
  public void onPlayerInteract(final PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) {
      return;
    }
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getClickedBlock() != null && isBed(event.getClickedBlock().getType())) {
      trackBedSpawn(event.getPlayer());
    }
    if (consumeLifeHeart(event.getPlayer(), event.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler
  public void onPlayerJoin(final PlayerJoinEvent event) {
    if (isLifeStealEnabled()) {
      initializeLifeStealProfile(event.getPlayer());
      Bukkit.getScheduler().runTaskLater(this, () -> applyLifeStealHealth(event.getPlayer()), 1L);
    }
    if (isPingOptimizerEnabled()) {
      Bukkit.getScheduler().runTaskLater(this, () -> applyTcpNoDelay(event.getPlayer()), 20L);
    }
  }

  @EventHandler
  public void onPlayerQuit(final PlayerQuitEvent event) {
    pendingAdds.remove(event.getPlayer().getUniqueId());
  }

  private void setupEconomy() {
    RegisteredServiceProvider<Economy> registration =
        getServer().getServicesManager().getRegistration(Economy.class);
    if (registration == null) {
      economy = null;
      getLogger().warning("Vault is loaded, but no economy provider is registered.");
      return;
    }
    economy = registration.getProvider();
    getLogger().info("Using economy provider: " + economy.getName());
  }

  private void registerCommand(final String commandName) {
    if (getCommand(commandName) == null) {
      getLogger().warning("Command not found in plugin.yml: " + commandName);
      return;
    }
    getCommand(commandName).setExecutor(this);
    getCommand(commandName).setTabCompleter(this);
  }

  private boolean matchesCommand(final Command command, final String label, final String... names) {
    for (String name : names) {
      if (name.equalsIgnoreCase(command.getName()) || name.equalsIgnoreCase(label)) {
        return true;
      }
    }
    return false;
  }

  private void initializeLifeStealKeys() {
    lifeHeartKey = new NamespacedKey(this, "lifesteal_heart");
    reviveBeaconKey = new NamespacedKey(this, "lifesteal_revive_beacon");
    soulPlayerKey = new NamespacedKey(this, "lifesteal_soul_player");
    soulNameKey = new NamespacedKey(this, "lifesteal_soul_name");
    heartRecipeKey = new NamespacedKey(this, "lifesteal_heart_recipe");
    reviveRecipeKey = new NamespacedKey(this, "lifesteal_revive_beacon_recipe");
  }

  private void migrateLegacyKitShopData() {
    if (kitsFile.exists()) {
      return;
    }

    File legacyDataFolder = new File(getDataFolder().getParentFile(), "KitShopPlugin");
    File legacyKitsFile = new File(legacyDataFolder, "kits.yml");
    if (!legacyKitsFile.exists()) {
      return;
    }

    if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
      getLogger().warning("Could not create MineLifeCore data folder for kit migration.");
      return;
    }

    try {
      Files.copy(legacyKitsFile.toPath(), kitsFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
      getLogger().info("Migrated kit shop data from KitShopPlugin/kits.yml to MineLifeCore/kits.yml.");
    } catch (IOException exception) {
      getLogger().log(Level.WARNING, "Could not migrate legacy kit shop data.", exception);
    }
  }

  private void upgradeStockMessages() {
    if (getConfig().isSet("messages.invalid-stock")) {
      return;
    }
    getConfig().set("messages.holding-air",
        "&cHold the shulker kit you want to sell, then run &e/kitshop add <price> <stock>&c.");
    getConfig().set("messages.invalid-stock", "&cUse a stock amount of at least 1.");
    getConfig().set("messages.item-added",
        "&aAdded kit &f%item% &afor &e%price% &awith &e%stock% &ain stock.");
    getConfig().set("messages.bought-item", "&aBought kit &f%item% &afor &e%price%&a. &e%stock% &aleft.");
    getConfig().set("messages.bought-last-item",
        "&aBought the last &f%item% &akit for &e%price%&a. It is now out of stock.");
    getConfig().set("messages.out-of-stock", "&cThat kit is out of stock.");
    getConfig().set("messages.add-chat-prompt",
        "&aType the price and stock for kit &f%item%&a, like &e500 10&a, or type &ccancel&a.");
    saveConfig();
  }

  private void upgradeAccessMessages() {
    String currentMessage = getConfig().getString("messages.no-permission", "");
    if (!currentMessage.contains("Only server ops can use /kitshop")) {
      return;
    }
    getConfig().set("messages.no-permission", "&cOnly server ops can manage /kitshop.");
    saveConfig();
  }

  private void upgradeMoneyMessages() {
    boolean changed = false;
    changed |= setDefaultMessage(
        "messages.money-usage",
        "&eUsage: /money | /money add/remove <amount> | /money <player> add/remove <amount>");
    changed |= setDefaultMessage("messages.money-balance", "&aBalance: &e%balance%");
    changed |= setDefaultMessage("messages.money-balance-other", "&a%player%'s balance: &e%balance%");
    changed |= setDefaultMessage("messages.money-added", "&aAdded &e%amount% &ato &f%player%&a. Balance: &e%balance%&a.");
    changed |= setDefaultMessage(
        "messages.money-removed",
        "&aRemoved &e%amount% &afrom &f%player%&a. Balance: &e%balance%&a.");
    if (changed) {
      saveConfig();
    }
  }

  private void upgradeCoreMessages() {
    boolean changed = false;
    changed |= setDefaultMessage("messages.ping-self", "&aPing: &e%ping%ms &8| &aTPS: &e%tps%");
    changed |= setDefaultMessage("messages.ping-other", "&a%player%'s ping: &e%ping%ms &8| &aTPS: &e%tps%");
    changed |= setDefaultMessage("messages.player-not-online", "&cThat player is not online.");
    changed |= setDefaultMessage(
        "messages.core-status",
        "&aTPS: &e%tps% &8| &aOnline: &e%online% &8| &aAverage ping: &e%avg_ping%ms &8| &aHighest ping: &e%max_ping%ms");
    changed |= setDefaultMessage(
        "messages.core-safe-optimizer",
        "&aSafe latency monitor is active. No mobs, chunks, items, or gameplay settings are changed.");
    changed |= setDefaultMessage(
        "messages.ping-optimizer-status",
        "&aPing optimizer: &e%state% &8| &aTCP_NODELAY applied: &e%applied%/%online%");
    changed |= setDefaultMessage("messages.server-stop-usage", "&eUsage: /server stop or /mlcore stop");
    changed |= setDefaultMessage("messages.server-stop-no-permission", "&cOnly server ops can stop the server.");
    changed |= setDefaultMessage("messages.server-stop-starting", "&cStopping the server now.");
    if ("&eUsage: /server stop".equals(getConfig().getString("messages.server-stop-usage", ""))) {
      getConfig().set("messages.server-stop-usage", "&eUsage: /server stop or /mlcore stop");
      changed = true;
    }
    String prefix = getConfig().getString("messages.prefix", "");
    if (prefix.contains("[&dKitShop&8]")) {
      getConfig().set("messages.prefix", "&8[&bMineLife Core&8]&r ");
      changed = true;
    }
    if (changed) {
      saveConfig();
    }
  }

  private void upgradePingOptimizerSettings() {
    boolean changed = false;
    if (!getConfig().isSet("settings.ping-optimizer.enabled")) {
      getConfig().set("settings.ping-optimizer.enabled", true);
      changed = true;
    }
    if (!getConfig().isSet("settings.ping-optimizer.reapply-seconds")) {
      getConfig().set("settings.ping-optimizer.reapply-seconds", 60);
      changed = true;
    }
    if (changed) {
      saveConfig();
    }
  }

  private void upgradeLifeStealSettings() {
    boolean changed = false;
    changed |= setDefaultConfig("lifesteal.enabled", true);
    changed |= setDefaultConfig("lifesteal.starting-hearts", 10);
    changed |= setDefaultConfig("lifesteal.minimum-withdraw-hearts", 1);
    changed |= setDefaultConfig("lifesteal.maximum-hearts", 20);
    changed |= setDefaultConfig("lifesteal.lose-hearts-on-non-player-death", true);
    if (!getConfig().getBoolean("lifesteal.lose-hearts-on-non-player-death", true)) {
      getConfig().set("lifesteal.lose-hearts-on-non-player-death", true);
      changed = true;
    }
    changed |= setDefaultConfig("lifesteal.ban-on-elimination", true);
    changed |= setDefaultConfig("lifesteal.ban-reason", "Ran out of hearts.");
    if ("Eliminated from LifeSteal.".equals(getConfig().getString("lifesteal.ban-reason", ""))) {
      getConfig().set("lifesteal.ban-reason", "Ran out of hearts.");
      changed = true;
    }
    changed |= setDefaultConfig("lifesteal.eliminate-to-spectator", false);
    if (getConfig().getBoolean("lifesteal.ban-on-elimination", true)
        && getConfig().getBoolean("lifesteal.eliminate-to-spectator", false)) {
      getConfig().set("lifesteal.eliminate-to-spectator", false);
      changed = true;
    }
    changed |= setDefaultConfig("lifesteal.default-respawn.world", "MyMinecraftServer");
    changed |= setDefaultConfig("lifesteal.default-respawn.x", 900705.509);
    changed |= setDefaultConfig("lifesteal.default-respawn.y", 199.0);
    changed |= setDefaultConfig("lifesteal.default-respawn.z", 9000253.545);
    changed |= setDefaultConfig("lifesteal.default-respawn.yaw", -90.4);
    changed |= setDefaultConfig("lifesteal.default-respawn.pitch", 0.1);
    changed |= setDefaultConfig("lifesteal.withdraw.enabled", true);
    changed |= setDefaultConfig("lifesteal.recipes.heart.enabled", true);
    changed |= setDefaultConfig("lifesteal.recipes.revive-beacon.enabled", true);
    changed |= setDefaultMessage("messages.lifesteal-disabled", "&cLifeSteal is disabled.");
    changed |= setDefaultMessage("messages.lifesteal-status", "&a%player% hearts: &e%hearts%&a/&e%max_hearts%&a. Eliminated: &e%eliminated%&a.");
    changed |= setDefaultMessage("messages.lifesteal-lost-heart", "&c%player% lost a heart. Hearts left: &e%hearts%&c.");
    changed |= setDefaultMessage("messages.lifesteal-stole-heart", "&a%killer% made &f%victim% &adrop a heart.");
    if (getConfig().getString("messages.lifesteal-stole-heart", "").contains("stole a heart")) {
      getConfig().set("messages.lifesteal-stole-heart", "&a%killer% made &f%victim% &adrop a heart.");
      changed = true;
    }
    changed |= setDefaultMessage("messages.lifesteal-killer-max", "&e%killer% is already at the heart limit.");
    changed |= setDefaultMessage("messages.lifesteal-eliminated", "&c%player% ran out of hearts and has been banned.");
    if ("&c%player% has been eliminated.".equals(getConfig().getString("messages.lifesteal-eliminated", ""))
        || "&c%player% has been eliminated and banned.".equals(getConfig().getString("messages.lifesteal-eliminated", ""))) {
      getConfig().set("messages.lifesteal-eliminated", "&c%player% ran out of hearts and has been banned.");
      changed = true;
    }
    changed |= setDefaultMessage("messages.lifesteal-bed-spawn-set", "&aBed spawn tracked. You will respawn at your bed.");
    changed |= setDefaultMessage("messages.lifesteal-withdraw-usage", "&eUsage: /withdraw [amount]");
    changed |= setDefaultMessage("messages.lifesteal-withdraw-disabled", "&cHeart withdrawing is disabled.");
    changed |= setDefaultMessage(
        "messages.lifesteal-not-enough-hearts",
        "&cYou must keep at least &e%minimum% &cheart. You can withdraw &e%extra% &cmore.");
    if (getConfig().getString("messages.lifesteal-not-enough-hearts", "").contains("extra hearts to withdraw")) {
      getConfig().set(
          "messages.lifesteal-not-enough-hearts",
          "&cYou must keep at least &e%minimum% &cheart. You can withdraw &e%extra% &cmore.");
      changed = true;
    }
    changed |= setDefaultMessage("messages.lifesteal-withdrew", "&aWithdrew &e%amount% &aheart(s). Hearts left: &e%hearts%&a.");
    changed |= setDefaultMessage("messages.lifesteal-heart-max", "&cYou are already at the heart limit.");
    changed |= setDefaultMessage("messages.lifesteal-heart-used", "&aYou gained a heart. Hearts: &e%hearts%&a/&e%max_hearts%&a.");
    changed |= setDefaultMessage("messages.lifesteal-eliminated-use", "&cYou are eliminated and need to be revived first.");
    changed |= setDefaultMessage("messages.lifesteal-revive-usage", "&eUsage: /revive <player>");
    changed |= setDefaultMessage("messages.lifesteal-not-eliminated", "&cThat player is not eliminated.");
    changed |= setDefaultMessage("messages.lifesteal-missing-revive-items", "&cYou need a Revive Beacon and that player's Soul item.");
    changed |= setDefaultMessage("messages.lifesteal-revived", "&a%player% has been revived with &e%hearts% &ahearts.");
    changed |= setDefaultMessage("messages.lifesteal-unbanned", "&a%player% has been unbanned from LifeSteal with &e%hearts% &ahearts.");
    String lifeStealAdminUsage = "&eUsage: /lifesteal [status|set <player> <hearts>|revive <player>|unban <player>|reload]";
    changed |= setDefaultMessage("messages.lifesteal-admin-usage", lifeStealAdminUsage);
    if ("&eUsage: /lifesteal [status|set <player> <hearts>|revive <player>|reload]"
        .equals(getConfig().getString("messages.lifesteal-admin-usage", ""))) {
      getConfig().set("messages.lifesteal-admin-usage", lifeStealAdminUsage);
      changed = true;
    }
    changed |= setDefaultMessage("messages.lifesteal-set", "&aSet &f%player% &ato &e%hearts% &ahearts.");
    if (changed) {
      saveConfig();
    }
  }

  private boolean setDefaultConfig(final String path, final Object value) {
    if (getConfig().isSet(path)) {
      return false;
    }
    getConfig().set(path, value);
    return true;
  }

  private boolean setDefaultMessage(final String path, final String value) {
    String current = getConfig().getString(path, "");
    if (current.equals(value)) {
      return false;
    }
    if (current.isEmpty()
        || current.contains("Usage: /money add <amount>")
        || current.contains("Added &e%amount% &ato your account")) {
      getConfig().set(path, value);
      return true;
    }
    return false;
  }

  private void handleMoneyCommand(final Player player, final String[] args) {
    if (economy == null) {
      player.sendMessage(prefixed("economy-missing"));
      return;
    }

    if (!isOperator(player)) {
      sendBalance(player, player);
      return;
    }

    if (args.length == 0) {
      sendBalance(player, player);
      return;
    }

    if (args.length == 1) {
      OfflinePlayer target = findTarget(args[0]);
      sendBalance(player, target);
      return;
    }

    if (args.length == 2 && isMoneyAction(args[0])) {
      changeBalance(player, player, args[0], args[1]);
      return;
    }

    if (args.length == 3 && isMoneyAction(args[1])) {
      OfflinePlayer target = findTarget(args[0]);
      changeBalance(player, target, args[1], args[2]);
      return;
    }

    player.sendMessage(prefixed("money-usage"));
  }

  private void sendBalance(final Player viewer, final OfflinePlayer target) {
    String key = target.getUniqueId().equals(viewer.getUniqueId()) ? "money-balance" : "money-balance-other";
    String responseMessage = message(key)
        .replace("%player%", displayName(target))
        .replace("%balance%", formatPrice(economy.getBalance(target)));
    viewer.sendMessage(color(message("prefix") + responseMessage));
  }

  private void changeBalance(
      final Player actor,
      final OfflinePlayer target,
      final String action,
      final String amountText) {
    Double amount = parsePrice(amountText);
    if (amount == null) {
      actor.sendMessage(prefixed("invalid-price"));
      return;
    }

    EconomyResponse response;
    String messageKey;
    if ("add".equalsIgnoreCase(action)) {
      response = economy.depositPlayer(target, amount);
      messageKey = "money-added";
    } else {
      response = economy.withdrawPlayer(target, amount);
      messageKey = "money-removed";
    }

    if (!response.transactionSuccess()) {
      actor.sendMessage(prefixed("economy-missing"));
      getLogger().warning("Vault rejected money " + action + " for " + displayName(target) + ": " + response.errorMessage);
      return;
    }

    String responseMessage = message(messageKey)
        .replace("%amount%", formatPrice(amount))
        .replace("%player%", displayName(target))
        .replace("%balance%", formatPrice(economy.getBalance(target)));
    actor.sendMessage(color(message("prefix") + responseMessage));
  }

  private void handlePingCommand(final Player player, final String[] args) {
    if (args.length == 0) {
      sendPing(player, player);
      return;
    }
    if (!isOperator(player) && !player.hasPermission("minelifecore.ping.other")) {
      sendPing(player, player);
      return;
    }
    Player target = Bukkit.getPlayerExact(args[0]);
    if (target == null) {
      player.sendMessage(prefixed("player-not-online"));
      return;
    }
    sendPing(player, target);
  }

  private void sendPing(final Player viewer, final Player target) {
    String key = viewer.getUniqueId().equals(target.getUniqueId()) ? "ping-self" : "ping-other";
    String responseMessage = message(key)
        .replace("%player%", target.getName())
        .replace("%ping%", String.valueOf(playerPing(target)))
        .replace("%tps%", formatTps(currentTps()));
    viewer.sendMessage(color(message("prefix") + responseMessage));
  }

  private void handleServerCommand(final CommandSender sender, final String[] args) {
    if (args.length != 1 || !"stop".equalsIgnoreCase(args[0])) {
      sender.sendMessage(prefixed("server-stop-usage"));
      return;
    }

    if (!canStopServer(sender)) {
      sender.sendMessage(prefixed("server-stop-no-permission"));
      return;
    }

    sender.sendMessage(prefixed("server-stop-starting"));
    Bukkit.getScheduler().runTask(this, () -> getServer().shutdown());
  }

  private boolean canStopServer(final CommandSender sender) {
    return sender.isOp() || sender.hasPermission("minelifecore.server.stop");
  }

  private void handleLifeStealCommand(final CommandSender sender, final String[] args) {
    if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
      if (args.length == 0 && sender instanceof Player player) {
        sendLifeStealStatus(sender, player);
        return;
      }
      if (args.length == 2 && canAdminLifeSteal(sender)) {
        sendLifeStealStatus(sender, findTarget(args[1]));
        return;
      }
      sender.sendMessage(prefixed("lifesteal-admin-usage"));
      return;
    }

    if ("help".equalsIgnoreCase(args[0])) {
      sender.sendMessage(prefixed("lifesteal-admin-usage"));
      return;
    }

    if (!canAdminLifeSteal(sender)) {
      sender.sendMessage(prefixed("no-permission"));
      return;
    }

    if ("reload".equalsIgnoreCase(args[0])) {
      reloadConfig();
      upgradeStockMessages();
      upgradeAccessMessages();
      upgradeMoneyMessages();
      upgradeCoreMessages();
      upgradePingOptimizerSettings();
      upgradeLifeStealSettings();
      loadLifeStealData();
      registerLifeStealRecipes();
      applyLifeStealToOnlinePlayers();
      startPingOptimizer();
      sender.sendMessage(prefixed("reloaded"));
      return;
    }

    if ("set".equalsIgnoreCase(args[0]) && args.length == 3) {
      OfflinePlayer target = findTarget(args[1]);
      Integer hearts = parseStock(args[2]);
      if (hearts == null && "0".equals(args[2])) {
        hearts = 0;
      }
      if (hearts == null) {
        sender.sendMessage(prefixed("invalid-stock"));
        return;
      }
      LifeStealProfile profile = getOrCreateLifeStealProfile(target);
      setProfileHearts(profile, hearts);
      saveLifeStealData();
      applyLifeStealIfOnline(target);
      sender.sendMessage(formatLifeStealMessage("lifesteal-set", displayName(target), displayName(target), "", profile.hearts(), 0, 0));
      return;
    }

    if ("revive".equalsIgnoreCase(args[0]) && args.length == 2) {
      OfflinePlayer target = findTarget(args[1]);
      reviveLifeStealPlayer(sender, target, false);
      return;
    }

    if ("unban".equalsIgnoreCase(args[0]) && args.length == 2) {
      unbanLifeStealPlayer(sender, args[1]);
      return;
    }

    sender.sendMessage(prefixed("lifesteal-admin-usage"));
  }

  private void handleWithdrawCommand(final Player player, final String[] args) {
    if (!isLifeStealEnabled()) {
      player.sendMessage(prefixed("lifesteal-disabled"));
      return;
    }
    if (!player.hasPermission("minelifecore.lifesteal.use")) {
      player.sendMessage(prefixed("no-permission"));
      return;
    }
    if (!getConfig().getBoolean("lifesteal.withdraw.enabled", true)) {
      player.sendMessage(prefixed("lifesteal-withdraw-disabled"));
      return;
    }
    if (args.length > 1) {
      player.sendMessage(prefixed("lifesteal-withdraw-usage"));
      return;
    }
    Integer amount = 1;
    if (args.length == 1) {
      amount = parseStock(args[0]);
    }
    if (amount == null) {
      player.sendMessage(prefixed("lifesteal-withdraw-usage"));
      return;
    }

    LifeStealProfile profile = initializeLifeStealProfile(player);
    if (profile.eliminated()) {
      player.sendMessage(prefixed("lifesteal-eliminated-use"));
      return;
    }

    int withdrawableHearts = Math.max(0, profile.hearts() - minimumWithdrawHearts());
    if (amount > withdrawableHearts) {
      player.sendMessage(formatLifeStealMessage(
          "lifesteal-not-enough-hearts",
          player.getName(),
          player.getName(),
          "",
          profile.hearts(),
          amount,
          withdrawableHearts));
      return;
    }

    ItemStack hearts = createLifeHeartItem(amount);
    if (!hasRoom(player.getInventory(), hearts)) {
      player.sendMessage(prefixed("inventory-full"));
      return;
    }

    profile.setHearts(profile.hearts() - amount);
    applyLifeStealHealth(player);
    saveLifeStealData();
    player.getInventory().addItem(hearts);
    player.sendMessage(formatLifeStealMessage(
        "lifesteal-withdrew",
        player.getName(),
        player.getName(),
        "",
        profile.hearts(),
        amount,
        withdrawableHearts - amount));
  }

  private void handleReviveCommand(final Player player, final String[] args) {
    if (!isLifeStealEnabled()) {
      player.sendMessage(prefixed("lifesteal-disabled"));
      return;
    }
    if (!player.hasPermission("minelifecore.lifesteal.use")) {
      player.sendMessage(prefixed("no-permission"));
      return;
    }
    if (args.length != 1) {
      player.sendMessage(prefixed("lifesteal-revive-usage"));
      return;
    }

    OfflinePlayer target = findTarget(args[0]);
    LifeStealProfile targetProfile = getOrCreateLifeStealProfile(target);
    if (!targetProfile.eliminated()) {
      player.sendMessage(prefixed("lifesteal-not-eliminated"));
      return;
    }

    if (!consumeReviveItems(player, target.getUniqueId())) {
      player.sendMessage(prefixed("lifesteal-missing-revive-items"));
      return;
    }

    reviveLifeStealPlayer(player, target, true);
  }

  private void handleLifeStealDeath(final PlayerDeathEvent event) {
    if (!isLifeStealEnabled()) {
      return;
    }

    Player victim = event.getEntity();
    Player killer = victim.getKiller();
    boolean playerKill = killer != null && !killer.getUniqueId().equals(victim.getUniqueId());
    boolean shouldLoseHeart = playerKill || getConfig().getBoolean("lifesteal.lose-hearts-on-non-player-death", false);
    if (!shouldLoseHeart) {
      return;
    }

    LifeStealProfile victimProfile = initializeLifeStealProfile(victim);
    if (victimProfile.hearts() <= 0 || victimProfile.eliminated()) {
      return;
    }

    victimProfile.setHearts(Math.max(0, victimProfile.hearts() - 1));
    dropLifeStealItem(victim, createLifeHeartItem(1));
    if (victimProfile.hearts() <= 0) {
      victimProfile.setEliminated(true);
      dropLifeStealItem(victim, createSoulItem(victim));
      Bukkit.getScheduler().runTaskLater(this, () -> banEliminatedPlayer(victim), 1L);
      broadcastLifeSteal("lifesteal-eliminated", victim.getName(), victim.getName(), "", 0, 0, 0);
    } else {
      broadcastLifeSteal("lifesteal-lost-heart", victim.getName(), victim.getName(), "", victimProfile.hearts(), 0, 0);
    }

    if (playerKill) {
      broadcastLifeSteal("lifesteal-stole-heart", victim.getName(), victim.getName(), killer.getName(), victimProfile.hearts(), 0, 0);
    }

    saveLifeStealData();
  }

  private boolean consumeLifeHeart(final Player player, final ItemStack item) {
    if (!isLifeHeartItem(item)) {
      return false;
    }
    if (!isLifeStealEnabled()) {
      player.sendMessage(prefixed("lifesteal-disabled"));
      return true;
    }
    if (!player.hasPermission("minelifecore.lifesteal.use")) {
      player.sendMessage(prefixed("no-permission"));
      return true;
    }

    LifeStealProfile profile = initializeLifeStealProfile(player);
    if (profile.eliminated()) {
      player.sendMessage(prefixed("lifesteal-eliminated-use"));
      return true;
    }
    if (profile.hearts() >= maximumHearts()) {
      player.sendMessage(prefixed("lifesteal-heart-max"));
      return true;
    }

    removeOneFromMainHand(player);
    profile.setHearts(Math.min(maximumHearts(), profile.hearts() + 1));
    applyLifeStealHealth(player);
    saveLifeStealData();
    player.sendMessage(formatLifeStealMessage("lifesteal-heart-used", player.getName(), player.getName(), "", profile.hearts(), 1, 0));
    return true;
  }

  private void dropLifeStealItem(final Player player, final ItemStack item) {
    if (!isRealItem(item)) {
      return;
    }
    player.getWorld().dropItemNaturally(player.getLocation(), item);
  }

  private void sendLifeStealStatus(final CommandSender sender, final OfflinePlayer target) {
    LifeStealProfile profile = getOrCreateLifeStealProfile(target);
    sender.sendMessage(formatLifeStealMessage("lifesteal-status", displayName(target), displayName(target), "", profile.hearts(), 0, 0)
        .replace("%eliminated%", String.valueOf(profile.eliminated())));
  }

  private void reviveLifeStealPlayer(final CommandSender sender, final OfflinePlayer target, final boolean broadcast) {
    LifeStealProfile profile = getOrCreateLifeStealProfile(target);
    if (!profile.eliminated()) {
      sender.sendMessage(prefixed("lifesteal-not-eliminated"));
      return;
    }

    profile.setHearts(startingHearts());
    profile.setEliminated(false);
    saveLifeStealData();
    pardonLifeStealPlayer(displayName(target));
    applyLifeStealIfOnline(target);
    String messageText = formatLifeStealMessage("lifesteal-revived", displayName(target), displayName(target), "", profile.hearts(), 0, 0);
    if (broadcast) {
      Bukkit.broadcastMessage(messageText);
    } else {
      sender.sendMessage(messageText);
    }
  }

  private void unbanLifeStealPlayer(final CommandSender sender, final String requestedName) {
    String playerName = sanitizePlayerName(requestedName);
    if (playerName.isBlank()) {
      sender.sendMessage(prefixed("lifesteal-admin-usage"));
      return;
    }

    OfflinePlayer target = findTarget(playerName);
    List<Map.Entry<UUID, LifeStealProfile>> matchingProfiles = matchingLifeStealProfiles(playerName);
    if (matchingProfiles.isEmpty()) {
      matchingProfiles = List.of(Map.entry(target.getUniqueId(), getOrCreateLifeStealProfile(target)));
    }

    int restoredHearts = startingHearts();
    for (Map.Entry<UUID, LifeStealProfile> entry : matchingProfiles) {
      LifeStealProfile profile = entry.getValue();
      if (profile.eliminated() || profile.hearts() < 1) {
        setProfileHearts(profile, startingHearts());
      } else {
        profile.setEliminated(false);
      }
      profile.setName(sanitizePlayerName(profile.name()).isBlank() ? playerName : sanitizePlayerName(profile.name()));
      restoredHearts = profile.hearts();
      applyLifeStealIfOnline(entry.getKey());
      pardonLifeStealPlayer(profile.name());
    }

    saveLifeStealData();
    pardonLifeStealPlayer(playerName);
    pardonLifeStealPlayer(displayName(target));
    applyLifeStealIfOnline(target);
    sender.sendMessage(formatLifeStealMessage(
        "lifesteal-unbanned",
        playerName,
        playerName,
        "",
        restoredHearts,
        0,
        0));
  }

  private boolean canAdminLifeSteal(final CommandSender sender) {
    return sender.isOp() || sender.hasPermission("minelifecore.lifesteal.admin");
  }

  private void banEliminatedPlayer(final Player player) {
    if (!getConfig().getBoolean("lifesteal.ban-on-elimination", true)) {
      applyLifeStealHealth(player);
      return;
    }
    String reason = ChatColor.stripColor(color(getConfig().getString(
        "lifesteal.ban-reason",
        "Ran out of hearts.")));
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player.getName() + " " + reason);
  }

  private void pardonLifeStealPlayer(final String playerName) {
    String cleanName = sanitizePlayerName(playerName);
    if (cleanName.isBlank() || cleanName.length() > 16) {
      return;
    }
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + cleanName);
  }

  private void trackBedSpawn(final Player player) {
    LifeStealProfile profile = initializeLifeStealProfile(player);
    if (profile.hasBedSpawn()) {
      return;
    }
    profile.setHasBedSpawn(true);
    saveLifeStealData();
    player.sendMessage(prefixed("lifesteal-bed-spawn-set"));
  }

  private boolean isBed(final Material material) {
    return material != null && material.name().endsWith("_BED");
  }

  private Location defaultLifeStealRespawnLocation() {
    String worldName = getConfig().getString("lifesteal.default-respawn.world", "MyMinecraftServer");
    World world = Bukkit.getWorld(worldName == null ? "" : worldName);
    if (world == null && !Bukkit.getWorlds().isEmpty()) {
      world = Bukkit.getWorlds().get(0);
    }
    double x = getConfig().getDouble("lifesteal.default-respawn.x", 900705.509);
    double y = getConfig().getDouble("lifesteal.default-respawn.y", 199.0);
    double z = getConfig().getDouble("lifesteal.default-respawn.z", 9000253.545);
    float yaw = (float) getConfig().getDouble("lifesteal.default-respawn.yaw", -90.4);
    float pitch = (float) getConfig().getDouble("lifesteal.default-respawn.pitch", 0.1);
    return new Location(world, x, y, z, yaw, pitch);
  }

  private void handleCoreCommand(final Player player, final String label, final String[] args) {
    if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
      sendCoreStatus(player);
      return;
    }
    if ("reload".equalsIgnoreCase(args[0])) {
      if (!isOperator(player) && !player.hasPermission("minelifecore.admin")) {
        player.sendMessage(prefixed("no-permission"));
        return;
      }
      reloadConfig();
      upgradeStockMessages();
      upgradeAccessMessages();
      upgradeMoneyMessages();
      upgradeCoreMessages();
      upgradePingOptimizerSettings();
      upgradeLifeStealSettings();
      itemsPerPage = Math.min(45, Math.max(9, getConfig().getInt("settings.items-per-page", 45)));
      setupEconomy();
      loadListings();
      loadLifeStealData();
      registerLifeStealRecipes();
      applyLifeStealToOnlinePlayers();
      startPingOptimizer();
      player.sendMessage(prefixed("reloaded"));
      return;
    }
    if ("stop".equalsIgnoreCase(args[0])) {
      handleServerCommand(player, new String[] {"stop"});
      return;
    }
    sendCoreHelp(player, label);
  }

  private void sendCoreStatus(final Player player) {
    int onlineCount = Bukkit.getOnlinePlayers().size();
    int totalPing = 0;
    int maxPing = 0;
    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
      int ping = playerPing(onlinePlayer);
      totalPing += ping;
      maxPing = Math.max(maxPing, ping);
    }
    int averagePing = onlineCount == 0 ? 0 : Math.round((float) totalPing / onlineCount);
    String responseMessage = message("core-status")
        .replace("%tps%", formatTps(currentTps()))
        .replace("%online%", String.valueOf(onlineCount))
        .replace("%avg_ping%", String.valueOf(averagePing))
        .replace("%max_ping%", String.valueOf(maxPing));
    player.sendMessage(color(message("prefix") + responseMessage));
    player.sendMessage(prefixed("core-safe-optimizer"));
    player.sendMessage(pingOptimizerStatus());
  }

  private void sendCoreHelp(final Player player, final String label) {
    player.sendMessage(color("&bMineLife Core commands:"));
    player.sendMessage(color("&e/" + label + " status &7- show TPS and ping summary"));
    player.sendMessage(color("&e/ping [player] &7- show ping"));
    player.sendMessage(color("&e/kitshop &7- open the kit shop"));
    player.sendMessage(color("&e/money &7- show your balance"));
    if (isOperator(player) || player.hasPermission("minelifecore.admin")) {
      player.sendMessage(color("&e/" + label + " reload &7- reload MineLife Core"));
    }
    if (canStopServer(player)) {
      player.sendMessage(color("&e/server stop &7- stop the server"));
      player.sendMessage(color("&e/" + label + " stop &7- stop the server"));
    }
    if (isOperator(player)) {
      player.sendMessage(color("&e/money <player> add/remove <amount> &7- manage money"));
    }
  }

  private void loadLifeStealData() {
    lifeStealProfiles.clear();
    if (lifeStealFile == null || !lifeStealFile.exists()) {
      return;
    }

    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(lifeStealFile);
    ConfigurationSection section = yaml.getConfigurationSection("players");
    if (section == null) {
      return;
    }

    for (String idText : section.getKeys(false)) {
      try {
        UUID playerId = UUID.fromString(idText);
        String name = section.getString(idText + ".name", playerId.toString());
        int hearts = Math.max(0, Math.min(maximumHearts(), section.getInt(idText + ".hearts", startingHearts())));
        boolean eliminated = section.getBoolean(idText + ".eliminated", hearts <= 0);
        boolean hasBedSpawn = section.getBoolean(idText + ".has-bed-spawn", false);
        lifeStealProfiles.put(playerId, new LifeStealProfile(name, hearts, eliminated, hasBedSpawn));
      } catch (IllegalArgumentException exception) {
        getLogger().warning("Skipped invalid LifeSteal player id: " + idText);
      }
    }
  }

  private void saveLifeStealData() {
    if (lifeStealFile == null) {
      return;
    }
    if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
      getLogger().warning("Could not create MineLifeCore data folder for lifesteal.yml.");
      return;
    }

    YamlConfiguration yaml = new YamlConfiguration();
    for (Map.Entry<UUID, LifeStealProfile> entry : lifeStealProfiles.entrySet()) {
      String path = "players." + entry.getKey();
      LifeStealProfile profile = entry.getValue();
      yaml.set(path + ".name", profile.name());
      yaml.set(path + ".hearts", profile.hearts());
      yaml.set(path + ".eliminated", profile.eliminated());
      yaml.set(path + ".has-bed-spawn", profile.hasBedSpawn());
    }

    try {
      yaml.save(lifeStealFile);
    } catch (IOException exception) {
      getLogger().log(Level.SEVERE, "Could not save LifeSteal player data.", exception);
    }
  }

  private void registerLifeStealRecipes() {
    removeLifeStealRecipes();
    if (!isLifeStealEnabled()) {
      return;
    }

    if (getConfig().getBoolean("lifesteal.recipes.heart.enabled", true)) {
      ShapedRecipe heartRecipe = new ShapedRecipe(heartRecipeKey, createLifeHeartItem(1));
      heartRecipe.shape("RDR", "DTD", "RDR");
      heartRecipe.setIngredient('R', Material.REDSTONE_BLOCK);
      heartRecipe.setIngredient('D', Material.DIAMOND_BLOCK);
      heartRecipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
      addRecipeSafely(heartRecipe);
    }

    if (getConfig().getBoolean("lifesteal.recipes.revive-beacon.enabled", true)) {
      ShapedRecipe reviveRecipe = new ShapedRecipe(reviveRecipeKey, createReviveBeaconItem(1));
      reviveRecipe.shape("SNS", "DBD", "STS");
      reviveRecipe.setIngredient('S', Material.SOUL_SAND);
      reviveRecipe.setIngredient('N', Material.NETHER_STAR);
      reviveRecipe.setIngredient('D', Material.DIAMOND_BLOCK);
      reviveRecipe.setIngredient('B', Material.BEACON);
      reviveRecipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
      addRecipeSafely(reviveRecipe);
    }
  }

  private void removeLifeStealRecipes() {
    if (heartRecipeKey != null) {
      Bukkit.removeRecipe(heartRecipeKey);
    }
    if (reviveRecipeKey != null) {
      Bukkit.removeRecipe(reviveRecipeKey);
    }
  }

  private void addRecipeSafely(final ShapedRecipe recipe) {
    try {
      Bukkit.addRecipe(recipe);
    } catch (IllegalStateException exception) {
      getLogger().fine("LifeSteal recipe was already registered: " + recipe.getKey());
    }
  }

  private void applyLifeStealToOnlinePlayers() {
    if (!isLifeStealEnabled()) {
      return;
    }
    for (Player player : Bukkit.getOnlinePlayers()) {
      initializeLifeStealProfile(player);
      applyLifeStealHealth(player);
    }
  }

  private void applyLifeStealIfOnline(final OfflinePlayer player) {
    Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
    if (onlinePlayer != null) {
      applyLifeStealHealth(onlinePlayer);
    }
  }

  private void applyLifeStealIfOnline(final UUID playerId) {
    Player onlinePlayer = Bukkit.getPlayer(playerId);
    if (onlinePlayer != null) {
      applyLifeStealHealth(onlinePlayer);
    }
  }

  private void applyLifeStealHealth(final Player player) {
    if (!isLifeStealEnabled()) {
      return;
    }

    LifeStealProfile profile = initializeLifeStealProfile(player);
    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
    if (maxHealth != null) {
      double hearts = Math.max(1, Math.min(maximumHearts(), Math.max(0, profile.hearts())));
      double healthValue = hearts * 2.0;
      maxHealth.setBaseValue(healthValue);
      if (!player.isDead() && player.getHealth() > healthValue) {
        player.setHealth(healthValue);
      }
    }

    if (profile.eliminated() && getConfig().getBoolean("lifesteal.ban-on-elimination", true)) {
      Bukkit.getScheduler().runTask(this, () -> banEliminatedPlayer(player));
    } else if (profile.eliminated() && getConfig().getBoolean("lifesteal.eliminate-to-spectator", false)) {
      player.setGameMode(GameMode.SPECTATOR);
    } else if (!profile.eliminated() && player.getGameMode() == GameMode.SPECTATOR) {
      player.setGameMode(GameMode.SURVIVAL);
    }
  }

  private LifeStealProfile initializeLifeStealProfile(final Player player) {
    LifeStealProfile profile = lifeStealProfiles.get(player.getUniqueId());
    if (profile == null) {
      int currentHearts = currentBaseHearts(player);
      int starting = startingHearts();
      int initialHearts = currentHearts > 0 ? Math.min(maximumHearts(), currentHearts) : starting;
      profile = new LifeStealProfile(player.getName(), initialHearts, initialHearts <= 0, false);
      lifeStealProfiles.put(player.getUniqueId(), profile);
      saveLifeStealData();
    } else {
      profile.setName(player.getName());
    }
    return profile;
  }

  private LifeStealProfile getOrCreateLifeStealProfile(final OfflinePlayer player) {
    if (player instanceof Player onlinePlayer) {
      return initializeLifeStealProfile(onlinePlayer);
    }
    return lifeStealProfiles.computeIfAbsent(
        player.getUniqueId(),
        ignored -> new LifeStealProfile(displayName(player), startingHearts(), false, false));
  }

  private void setProfileHearts(final LifeStealProfile profile, final int hearts) {
    int clampedHearts = Math.max(0, Math.min(maximumHearts(), hearts));
    profile.setHearts(clampedHearts);
    profile.setEliminated(clampedHearts <= 0);
  }

  private int currentBaseHearts(final Player player) {
    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
    if (maxHealth == null) {
      return startingHearts();
    }
    return Math.max(1, (int) Math.round(maxHealth.getBaseValue() / 2.0));
  }

  private boolean isLifeStealEnabled() {
    return getConfig().getBoolean("lifesteal.enabled", true);
  }

  private int startingHearts() {
    return Math.max(1, getConfig().getInt("lifesteal.starting-hearts", 10));
  }

  private int minimumWithdrawHearts() {
    return Math.max(1, Math.min(maximumHearts(), getConfig().getInt("lifesteal.minimum-withdraw-hearts", 1)));
  }

  private int maximumHearts() {
    return Math.max(startingHearts(), getConfig().getInt("lifesteal.maximum-hearts", 20));
  }

  private ItemStack createLifeHeartItem(final int amount) {
    ItemStack item = new ItemStack(Material.RED_DYE, Math.max(1, amount));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(color("&cLife Heart"));
      meta.setLore(List.of(
          color("&7Right-click to gain one max heart."),
          color("&7Can be crafted or withdrawn with /withdraw.")));
      meta.getPersistentDataContainer().set(lifeHeartKey, PersistentDataType.BYTE, (byte) 1);
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack createReviveBeaconItem(final int amount) {
    ItemStack item = new ItemStack(Material.BEACON, Math.max(1, amount));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(color("&bRevive Beacon"));
      meta.setLore(List.of(
          color("&7Use /revive <player> while holding"),
          color("&7this and that player's Soul item.")));
      meta.getPersistentDataContainer().set(reviveBeaconKey, PersistentDataType.BYTE, (byte) 1);
      item.setItemMeta(meta);
    }
    return item;
  }

  private ItemStack createSoulItem(final Player player) {
    ItemStack item = new ItemStack(Material.ECHO_SHARD);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(color("&5Soul of " + player.getName()));
      meta.setLore(List.of(
          color("&7Required to revive this eliminated player."),
          color("&7Use with a Revive Beacon.")));
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(soulPlayerKey, PersistentDataType.STRING, player.getUniqueId().toString());
      data.set(soulNameKey, PersistentDataType.STRING, player.getName());
      item.setItemMeta(meta);
    }
    return item;
  }

  private boolean isLifeHeartItem(final ItemStack item) {
    return hasByteTag(item, lifeHeartKey);
  }

  private boolean isReviveBeaconItem(final ItemStack item) {
    return hasByteTag(item, reviveBeaconKey);
  }

  private boolean isSoulItemFor(final ItemStack item, final UUID playerId) {
    if (!isRealItem(item) || !item.hasItemMeta()) {
      return false;
    }
    String soulId = item.getItemMeta().getPersistentDataContainer().get(soulPlayerKey, PersistentDataType.STRING);
    return playerId.toString().equals(soulId);
  }

  private boolean hasByteTag(final ItemStack item, final NamespacedKey key) {
    return isRealItem(item)
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
  }

  private boolean consumeReviveItems(final Player player, final UUID targetId) {
    PlayerInventory inventory = player.getInventory();
    int beaconSlot = -1;
    int soulSlot = -1;
    ItemStack[] contents = inventory.getContents();
    for (int slot = 0; slot < contents.length; slot++) {
      ItemStack item = contents[slot];
      if (beaconSlot == -1 && isReviveBeaconItem(item)) {
        beaconSlot = slot;
      }
      if (soulSlot == -1 && isSoulItemFor(item, targetId)) {
        soulSlot = slot;
      }
      if (beaconSlot != -1 && soulSlot != -1) {
        break;
      }
    }

    if (beaconSlot == -1 || soulSlot == -1) {
      return false;
    }
    decrementInventorySlot(inventory, beaconSlot);
    decrementInventorySlot(inventory, soulSlot);
    return true;
  }

  private void decrementInventorySlot(final PlayerInventory inventory, final int slot) {
    ItemStack item = inventory.getItem(slot);
    if (!isRealItem(item)) {
      return;
    }
    if (item.getAmount() <= 1) {
      inventory.setItem(slot, null);
    } else {
      item.setAmount(item.getAmount() - 1);
    }
  }

  private void removeOneFromMainHand(final Player player) {
    ItemStack item = player.getInventory().getItemInMainHand();
    if (!isRealItem(item)) {
      return;
    }
    if (item.getAmount() <= 1) {
      player.getInventory().setItemInMainHand(null);
    } else {
      item.setAmount(item.getAmount() - 1);
    }
  }

  private void broadcastLifeSteal(
      final String key,
      final String player,
      final String victim,
      final String killer,
      final int hearts,
      final int amount,
      final int extra) {
    Bukkit.broadcastMessage(formatLifeStealMessage(key, player, victim, killer, hearts, amount, extra));
  }

  private String formatLifeStealMessage(
      final String key,
      final String player,
      final String victim,
      final String killer,
      final int hearts,
      final int amount,
      final int extra) {
    return color(message("prefix") + message(key)
        .replace("%player%", player)
        .replace("%victim%", victim)
        .replace("%killer%", killer)
        .replace("%hearts%", String.valueOf(Math.max(0, hearts)))
        .replace("%max_hearts%", String.valueOf(maximumHearts()))
        .replace("%amount%", String.valueOf(Math.max(0, amount)))
        .replace("%extra%", String.valueOf(Math.max(0, extra)))
        .replace("%minimum%", String.valueOf(minimumWithdrawHearts())));
  }

  private void startPingOptimizer() {
    stopPingOptimizer();
    if (!isPingOptimizerEnabled()) {
      return;
    }

    long intervalTicks = Math.max(10, getConfig().getInt("settings.ping-optimizer.reapply-seconds", 60)) * 20L;
    pingOptimizerTask = Bukkit.getScheduler().runTaskTimer(this, this::applyTcpNoDelayToOnlinePlayers, 20L, intervalTicks);
  }

  private void stopPingOptimizer() {
    if (pingOptimizerTask != null) {
      pingOptimizerTask.cancel();
      pingOptimizerTask = null;
    }
  }

  private boolean isPingOptimizerEnabled() {
    return getConfig().getBoolean("settings.ping-optimizer.enabled", true);
  }

  private int applyTcpNoDelayToOnlinePlayers() {
    int applied = 0;
    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
      if (applyTcpNoDelay(onlinePlayer)) {
        applied++;
      }
    }
    return applied;
  }

  private String pingOptimizerStatus() {
    int online = Bukkit.getOnlinePlayers().size();
    int applied = isPingOptimizerEnabled() ? applyTcpNoDelayToOnlinePlayers() : 0;
    String state = isPingOptimizerEnabled() ? "enabled" : "disabled";
    String responseMessage = message("ping-optimizer-status")
        .replace("%state%", state)
        .replace("%applied%", String.valueOf(applied))
        .replace("%online%", String.valueOf(online));
    return color(message("prefix") + responseMessage);
  }

  private boolean applyTcpNoDelay(final Player player) {
    Object channel = findNettyChannel(player);
    if (channel == null) {
      return false;
    }

    try {
      Class<?> channelOptionClass = Class.forName("io.netty.channel.ChannelOption");
      Object tcpNoDelay = channelOptionClass.getField("TCP_NODELAY").get(null);
      Object config = channel.getClass().getMethod("config").invoke(channel);
      Method setOption = findMethod(config.getClass(), "setOption", 2);
      if (setOption == null) {
        return false;
      }
      Object result = setOption.invoke(config, tcpNoDelay, Boolean.TRUE);
      return !(result instanceof Boolean success) || success;
    } catch (ReflectiveOperationException | LinkageError exception) {
      return false;
    }
  }

  private Method findMethod(final Class<?> type, final String name, final int parameterCount) {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
        return method;
      }
    }
    return null;
  }

  private Object findNettyChannel(final Player player) {
    try {
      Object handle = player.getClass().getMethod("getHandle").invoke(player);
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      return findNettyChannel(handle, 0, seen);
    } catch (ReflectiveOperationException exception) {
      return null;
    }
  }

  private Object findNettyChannel(final Object value, final int depth, final Set<Object> seen) {
    if (value == null || depth > 5 || seen.contains(value)) {
      return null;
    }
    seen.add(value);

    Class<?> type = value.getClass();
    if (isNettyChannel(type)) {
      return value;
    }

    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || shouldSkipField(field.getType())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object child = field.get(value);
          Object channel = findNettyChannel(child, depth + 1, seen);
          if (channel != null) {
            return channel;
          }
        } catch (IllegalAccessException | RuntimeException ignored) {
          // Some internal fields are not reflectively accessible on every server build.
        }
      }
    }
    return null;
  }

  private boolean isNettyChannel(final Class<?> type) {
    if (type == null || !type.getName().contains("io.netty.channel")) {
      return false;
    }
    return findMethod(type, "config", 0) != null;
  }

  private boolean shouldSkipField(final Class<?> type) {
    return type.isPrimitive()
        || type.isEnum()
        || type == String.class
        || Number.class.isAssignableFrom(type)
        || type == Boolean.class
        || type == Character.class
        || type == UUID.class
        || type.getName().startsWith("java.util.logging");
  }

  private boolean isMoneyAction(final String action) {
    return "add".equalsIgnoreCase(action) || "remove".equalsIgnoreCase(action);
  }

  @SuppressWarnings("deprecation")
  private OfflinePlayer findTarget(final String name) {
    String playerName = sanitizePlayerName(name);
    Player onlinePlayer = Bukkit.getPlayerExact(playerName);
    if (onlinePlayer != null) {
      return onlinePlayer;
    }
    for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
      if (sanitizePlayerName(offlinePlayer.getName()).equalsIgnoreCase(playerName)) {
        return offlinePlayer;
      }
    }
    UUID profileId = findLifeStealProfileIdByName(playerName);
    if (profileId != null) {
      return Bukkit.getOfflinePlayer(profileId);
    }
    return Bukkit.getOfflinePlayer(playerName);
  }

  private String displayName(final OfflinePlayer player) {
    String name = player.getName();
    return name == null ? player.getUniqueId().toString() : name;
  }

  private UUID findLifeStealProfileIdByName(final String name) {
    String playerName = sanitizePlayerName(name);
    UUID fallback = null;
    for (Map.Entry<UUID, LifeStealProfile> entry : lifeStealProfiles.entrySet()) {
      if (sanitizePlayerName(entry.getValue().name()).equalsIgnoreCase(playerName)) {
        if (entry.getValue().eliminated()) {
          return entry.getKey();
        }
        if (fallback == null) {
          fallback = entry.getKey();
        }
      }
    }
    return fallback;
  }

  private List<Map.Entry<UUID, LifeStealProfile>> matchingLifeStealProfiles(final String name) {
    String playerName = sanitizePlayerName(name);
    return lifeStealProfiles.entrySet().stream()
        .filter(entry -> sanitizePlayerName(entry.getValue().name()).equalsIgnoreCase(playerName))
        .toList();
  }

  private String sanitizePlayerName(final String name) {
    if (name == null) {
      return "";
    }
    return name.trim().replaceAll("^[^A-Za-z0-9_]+|[^A-Za-z0-9_]+$", "");
  }

  private void handlePendingPrice(final Player player, final UUID playerId, final String chatMessage) {
    ItemStack pendingItem = pendingAdds.get(playerId);
    if (pendingItem == null) {
      return;
    }

    if ("cancel".equalsIgnoreCase(chatMessage)) {
      pendingAdds.remove(playerId);
      player.sendMessage(prefixed("add-cancelled"));
      return;
    }

    KitAddRequest request = parseAddRequest(chatMessage);
    if (request == null) {
      player.sendMessage(prefixed("invalid-price"));
      player.sendMessage(prefixed("invalid-stock"));
      player.sendMessage(formatMessage("add-chat-prompt", pendingItem, 0));
      return;
    }

    pendingAdds.remove(playerId);
    addListing(player, pendingItem, request.price(), request.stock());
  }

  private void addHeldItem(final Player player, final double price, final int stock) {
    ItemStack heldItem = player.getInventory().getItemInMainHand();
    if (!isRealItem(heldItem)) {
      player.sendMessage(prefixed("holding-air"));
      return;
    }
    if (!isShulkerKit(heldItem)) {
      player.sendMessage(prefixed("not-shulker"));
      return;
    }
    addListing(player, heldItem.clone(), price, stock);
  }

  private void promptForPrice(final Player player, final ItemStack item) {
    pendingAdds.put(player.getUniqueId(), item);
    player.closeInventory();
    player.sendMessage(formatMessage("add-chat-prompt", item, 0));
  }

  private void addListing(final Player player, final ItemStack item, final double price, final int stock) {
    if (!isShulkerKit(item)) {
      player.sendMessage(prefixed("not-shulker"));
      return;
    }
    if (stock < 1) {
      player.sendMessage(prefixed("invalid-stock"));
      return;
    }
    item.setAmount(Math.max(1, item.getAmount()));
    listings.add(new KitListing(UUID.randomUUID().toString(), price, stock, item.clone()));
    saveListings();
    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    player.sendMessage(formatMessage("item-added", item, price, stock));
  }

  private void removeListing(final Player player, final int listingIndex) {
    KitListing removed = listings.remove(listingIndex);
    saveListings();
    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f);
    player.sendMessage(formatMessage("item-removed", removed.item(), removed.price(), removed.stock()));
  }

  private void buyListing(final Player player, final int listingIndex, final int page) {
    if (listingIndex < 0 || listingIndex >= listings.size()) {
      player.sendMessage(prefixed("out-of-stock"));
      openBuyerShop(player, Math.min(page, maxPage()));
      return;
    }
    KitListing listing = listings.get(listingIndex);
    if (listing.stock() < 1) {
      listings.remove(listingIndex);
      saveListings();
      player.sendMessage(prefixed("out-of-stock"));
      openBuyerShop(player, Math.min(page, maxPage()));
      return;
    }
    if (economy == null) {
      player.sendMessage(prefixed("economy-missing"));
      return;
    }
    if (!hasRoom(player.getInventory(), listing.item())) {
      player.sendMessage(prefixed("inventory-full"));
      return;
    }
    if (!economy.has(player, listing.price())) {
      player.sendMessage(formatMessage("not-enough-money", listing.item(), listing.price()));
      return;
    }

    EconomyResponse response = economy.withdrawPlayer(player, listing.price());
    if (!response.transactionSuccess()) {
      player.sendMessage(prefixed("economy-missing"));
      getLogger().warning("Vault rejected purchase for " + player.getName() + ": " + response.errorMessage);
      return;
    }

    player.getInventory().addItem(listing.item().clone());
    int remainingStock = listing.stock() - 1;
    if (remainingStock <= 0) {
      listings.remove(listingIndex);
    } else {
      listings.set(listingIndex, new KitListing(listing.id(), listing.price(), remainingStock, listing.item()));
    }
    saveListings();
    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
    player.sendMessage(formatMessage(
        remainingStock <= 0 ? "bought-last-item" : "bought-item",
        listing.item(),
        listing.price(),
        remainingStock));
    openBuyerShop(player, Math.min(page, maxPage()));
  }

  private void openAdminShop(final Player player, final int page) {
    openShop(player, ShopMode.ADMIN, page);
  }

  private void openBuyerShop(final Player player, final int page) {
    if (listings.isEmpty()) {
      player.sendMessage(prefixed("shop-empty"));
    }
    openShop(player, ShopMode.BUYER, page);
  }

  private void openShop(final Player player, final ShopMode mode, final int requestedPage) {
    int page = Math.max(0, Math.min(requestedPage, maxPage()));
    KitInventoryHolder holder = new KitInventoryHolder(mode, page);
    Inventory inventory = Bukkit.createInventory(
        holder,
        INVENTORY_SIZE,
        (mode == ShopMode.ADMIN ? ADMIN_TITLE : SHOP_TITLE) + ChatColor.GRAY + " " + (page + 1));
    holder.setInventory(inventory);

    int start = page * itemsPerPage;
    for (int slot = 0; slot < itemsPerPage; slot++) {
      int listingIndex = start + slot;
      if (listingIndex >= listings.size()) {
        break;
      }
      KitListing listing = listings.get(listingIndex);
      inventory.setItem(slot, displayItem(listing, mode));
    }

    if (page > 0) {
      inventory.setItem(PREVIOUS_SLOT, namedItem(Material.ARROW, "&ePrevious Page", List.of()));
    }
    inventory.setItem(INFO_SLOT, infoItem(mode));
    if (hasNextPage(page)) {
      inventory.setItem(NEXT_SLOT, namedItem(Material.ARROW, "&eNext Page", List.of()));
    }

    player.openInventory(inventory);
  }

  private ItemStack displayItem(final KitListing listing, final ShopMode mode) {
    ItemStack display = listing.item().clone();
    ItemMeta meta = display.getItemMeta();
    if (meta != null) {
      List<String> lore = meta.hasLore() && meta.getLore() != null
          ? new ArrayList<>(meta.getLore())
          : new ArrayList<>();
      lore.add(color("&8&m----------------"));
      lore.add(color("&dKit Price: &e" + formatPrice(listing.price())));
      lore.add(color("&dStock: &e" + listing.stock()));
      if (mode == ShopMode.ADMIN) {
        lore.add(color("&cClick to remove from kit shop."));
        lore.add(color("&8ID: " + listing.id().substring(0, 8)));
      } else {
        lore.add(color("&7Click to buy this shulker kit."));
      }
      meta.setLore(lore);
      display.setItemMeta(meta);
    }
    return display;
  }

  private ItemStack infoItem(final ShopMode mode) {
    if (mode == ShopMode.ADMIN) {
      return namedItem(
          Material.BOOK,
          "&dKit Shop Editor",
          List.of(
              "&7Only shulker boxes can be added.",
              "&7Click a shulker in your inventory",
              "&7then type price and stock in chat.",
              "&7Shulker contents and data are saved.",
              "&e/kitshop add <price> <stock> &7also works."));
    }
    return namedItem(
        Material.SHULKER_BOX,
        "&dKit Shop",
        List.of("&7Click a kit to buy the shulker.", "&7Uses the same Vault money as EconomyShopGUI."));
  }

  private ItemStack namedItem(final Material material, final String name, final List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(color(name));
      meta.setLore(lore.stream().map(this::color).toList());
      item.setItemMeta(meta);
    }
    return item;
  }

  private void loadListings() {
    listings.clear();
    if (!kitsFile.exists()) {
      saveListings();
      return;
    }

    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(kitsFile);
    ConfigurationSection section = yaml.getConfigurationSection("kits");
    if (section == null) {
      return;
    }

    for (String id : section.getKeys(false)) {
      ItemStack item = section.getItemStack(id + ".item");
      double price = section.getDouble(id + ".price", -1);
      int stock = section.getInt(id + ".stock", 1);
      if (isShulkerKit(item) && price > 0 && stock > 0) {
        listings.add(new KitListing(id, price, stock, item));
      } else {
        getLogger().warning("Skipped invalid kit shop item: " + id);
      }
    }
  }

  private void saveListings() {
    if (kitsFile == null) {
      return;
    }
    if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
      getLogger().warning("Could not create plugin data folder.");
      return;
    }

    YamlConfiguration yaml = new YamlConfiguration();
    for (KitListing listing : listings) {
      String path = "kits." + listing.id();
      yaml.set(path + ".price", listing.price());
      yaml.set(path + ".stock", listing.stock());
      yaml.set(path + ".item", listing.item());
    }

    try {
      yaml.save(kitsFile);
    } catch (IOException exception) {
      getLogger().log(Level.SEVERE, "Could not save kit shop items.", exception);
    }
  }

  private void sendHelp(final Player player, final String label) {
    player.sendMessage(color("&dKitShop commands:"));
    player.sendMessage(color("&e/" + label + " &7- open buyer view"));
    player.sendMessage(color("&e/" + label + " buy &7- open buyer view"));
    if (isOperator(player)) {
      player.sendMessage(color("&e/" + label + " edit &7- open the kit editor"));
      player.sendMessage(color("&e/" + label + " add <price> <stock> &7- add the held shulker kit"));
      player.sendMessage(color("&e/" + label + " reload &7- reload the kit file"));
    }
  }

  private boolean hasRoom(final PlayerInventory inventory, final ItemStack item) {
    int remaining = item.getAmount();
    for (ItemStack slotItem : inventory.getStorageContents()) {
      if (!isRealItem(slotItem)) {
        return true;
      }
      if (slotItem.isSimilar(item) && slotItem.getAmount() < slotItem.getMaxStackSize()) {
        remaining -= slotItem.getMaxStackSize() - slotItem.getAmount();
        if (remaining <= 0) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isOperator(final Player player) {
    return player.isOp();
  }

  private boolean isRealItem(final ItemStack item) {
    return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
  }

  private boolean isShulkerKit(final ItemStack item) {
    return isRealItem(item) && item.getType().name().endsWith("SHULKER_BOX");
  }

  private boolean hasNextPage(final int page) {
    return (page + 1) * itemsPerPage < listings.size();
  }

  private int maxPage() {
    if (listings.isEmpty()) {
      return 0;
    }
    return (listings.size() - 1) / itemsPerPage;
  }

  private Double parsePrice(final String value) {
    try {
      double price = Double.parseDouble(value.replace(",", ""));
      return price > 0 ? price : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private Integer parseStock(final String value) {
    try {
      int stock = Integer.parseInt(value.replace(",", ""));
      return stock > 0 ? stock : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private KitAddRequest parseAddRequest(final String value) {
    String[] parts = value.trim().split("\\s+");
    if (parts.length < 2) {
      return null;
    }
    Double price = parsePrice(parts[0]);
    Integer stock = parseStock(parts[1]);
    if (price == null || stock == null) {
      return null;
    }
    return new KitAddRequest(price, stock);
  }

  private String formatMessage(final String key, final ItemStack item, final double price) {
    return formatMessage(key, item, price, 0);
  }

  private String formatMessage(final String key, final ItemStack item, final double price, final int stock) {
    return color(message("prefix") + message(key)
        .replace("%amount%", String.valueOf(item.getAmount()))
        .replace("%item%", itemName(item))
        .replace("%price%", price > 0 ? formatPrice(price) : "")
        .replace("%stock%", String.valueOf(Math.max(0, stock))));
  }

  private String prefixed(final String key) {
    return color(message("prefix") + message(key));
  }

  private String message(final String key) {
    return getConfig().getString("messages." + key, "");
  }

  private String color(final String value) {
    return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
  }

  private String itemName(final ItemStack item) {
    ItemMeta meta = item.getItemMeta();
    if (meta != null && meta.hasDisplayName()) {
      return ChatColor.stripColor(meta.getDisplayName());
    }
    return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private String formatPrice(final double price) {
    if (economy != null) {
      return economy.format(price);
    }
    return "$" + PRICE_FORMAT.format(price);
  }

  private double currentTps() {
    try {
      Object result = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
      if (result instanceof double[] tpsValues && tpsValues.length > 0) {
        return Math.min(20.0, tpsValues[0]);
      }
    } catch (ReflectiveOperationException exception) {
      return 20.0;
    }
    return 20.0;
  }

  private String formatTps(final double tps) {
    return String.format(Locale.US, "%.2f", tps);
  }

  private int playerPing(final Player player) {
    try {
      Object result = player.getClass().getMethod("getPing").invoke(player);
      if (result instanceof Integer ping) {
        return Math.max(0, ping);
      }
    } catch (ReflectiveOperationException ignored) {
      // Fall through to Spigot's ping helper when Paper's method is absent from the compile API.
    }

    try {
      Object spigot = player.getClass().getMethod("spigot").invoke(player);
      Object result = spigot.getClass().getMethod("getPing").invoke(spigot);
      if (result instanceof Integer ping) {
        return Math.max(0, ping);
      }
    } catch (ReflectiveOperationException ignored) {
      return 0;
    }
    return 0;
  }

  private enum ShopMode {
    ADMIN,
    BUYER
  }

  private record KitListing(String id, double price, int stock, ItemStack item) {
  }

  private record KitAddRequest(double price, int stock) {
  }

  private static final class LifeStealProfile {
    private String name;
    private int hearts;
    private boolean eliminated;
    private boolean hasBedSpawn;

    private LifeStealProfile(
        final String name,
        final int hearts,
        final boolean eliminated,
        final boolean hasBedSpawn) {
      this.name = name;
      this.hearts = hearts;
      this.eliminated = eliminated;
      this.hasBedSpawn = hasBedSpawn;
    }

    private String name() {
      return name;
    }

    private void setName(final String name) {
      this.name = name;
    }

    private int hearts() {
      return hearts;
    }

    private void setHearts(final int hearts) {
      this.hearts = hearts;
    }

    private boolean eliminated() {
      return eliminated;
    }

    private void setEliminated(final boolean eliminated) {
      this.eliminated = eliminated;
    }

    private boolean hasBedSpawn() {
      return hasBedSpawn;
    }

    private void setHasBedSpawn(final boolean hasBedSpawn) {
      this.hasBedSpawn = hasBedSpawn;
    }
  }

  private static final class KitInventoryHolder implements InventoryHolder {
    private final ShopMode mode;
    private final int page;
    private Inventory inventory;

    private KitInventoryHolder(final ShopMode mode, final int page) {
      this.mode = mode;
      this.page = page;
    }

    private ShopMode mode() {
      return mode;
    }

    private int page() {
      return page;
    }

    private void setInventory(final Inventory inventory) {
      this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }
}
