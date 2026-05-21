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
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
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
  private File kitsFile;
  private Economy economy;
  private int itemsPerPage;
  private BukkitTask pingOptimizerTask;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    upgradeStockMessages();
    upgradeAccessMessages();
    upgradeMoneyMessages();
    upgradeCoreMessages();
    upgradePingOptimizerSettings();
    itemsPerPage = Math.min(45, Math.max(9, getConfig().getInt("settings.items-per-page", 45)));
    kitsFile = new File(getDataFolder(), "kits.yml");

    migrateLegacyKitShopData();
    setupEconomy();
    loadListings();

    getServer().getPluginManager().registerEvents(this, this);
    registerCommand("kitshop");
    registerCommand("money");
    registerCommand("minelifecore");
    registerCommand("ping");
    startPingOptimizer();
  }

  @Override
  public void onDisable() {
    stopPingOptimizer();
    saveListings();
  }

  @Override
  public boolean onCommand(
      final CommandSender sender,
      final Command command,
      final String label,
      final String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(color(message("players-only")));
      return true;
    }

    if ("money".equalsIgnoreCase(command.getName())) {
      handleMoneyCommand(player, args);
      return true;
    }
    if ("ping".equalsIgnoreCase(command.getName())) {
      handlePingCommand(player, args);
      return true;
    }
    if ("minelifecore".equalsIgnoreCase(command.getName())) {
      handleCoreCommand(player, label, args);
      return true;
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
    if ("minelifecore".equalsIgnoreCase(command.getName())) {
      if (args.length == 1) {
        List<String> completions = new ArrayList<>();
        completions.add("status");
        completions.add("help");
        if (sender.isOp()) {
          completions.add("reload");
        }
        return completions.stream()
            .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if ("ping".equalsIgnoreCase(command.getName())) {
      if (args.length == 1 && sender.isOp()) {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
            .toList();
      }
      return List.of();
    }

    if ("money".equalsIgnoreCase(command.getName())) {
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
  public void onPlayerJoin(final PlayerJoinEvent event) {
    if (!isPingOptimizerEnabled()) {
      return;
    }
    Bukkit.getScheduler().runTaskLater(this, () -> applyTcpNoDelay(event.getPlayer()), 20L);
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
      itemsPerPage = Math.min(45, Math.max(9, getConfig().getInt("settings.items-per-page", 45)));
      setupEconomy();
      loadListings();
      startPingOptimizer();
      player.sendMessage(prefixed("reloaded"));
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
    if (isOperator(player)) {
      player.sendMessage(color("&e/" + label + " reload &7- reload MineLife Core"));
      player.sendMessage(color("&e/money <player> add/remove <amount> &7- manage money"));
    }
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
    Player onlinePlayer = Bukkit.getPlayerExact(name);
    if (onlinePlayer != null) {
      return onlinePlayer;
    }
    return Bukkit.getOfflinePlayer(name);
  }

  private String displayName(final OfflinePlayer player) {
    String name = player.getName();
    return name == null ? player.getUniqueId().toString() : name;
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
