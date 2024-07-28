package org.krbpank.bankauto;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.map.MapView;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.http.HttpResponse;
import org.json.JSONObject;
import org.json.JSONArray;
import java.awt.image.BufferedImage;
import java.net.URL;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;
import org.apache.commons.lang3.RandomStringUtils;
import java.security.SecureRandom;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.awt.Graphics2D;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BankAuto extends JavaPlugin implements Listener {
    private File transactionFile;
    private FileConfiguration transactionConfig;
    private final HashMap<UUID, Integer> playerMaps = new HashMap<>();
    private final HashMap<UUID, Boolean> activeTransactions = new HashMap<>();
    private final HashMap<UUID, String> playerAddInfoMap = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        createTransactionFile();
        this.saveDefaultConfig();

        getConfig().addDefault("api.url", "http://14.185.8.50:3000/check");
        getConfig().addDefault("api.username", "defaultUsername");
        getConfig().addDefault("api.password", "defaultPassword");
        getConfig().addDefault("api.accountNumber", "defaultAccountNumber");
        getConfig().addDefault("bank.name", "mbbank");
        getConfig().addDefault("bank.accountNumber", "xxxxxxxxxx");
        getConfig().addDefault("bank.accountName", "xxxxxxxxxx");
        getConfig().options().copyDefaults(true);
        saveConfig();

        Objects.requireNonNull(getCommand("atm")).setExecutor(new ATMCommand(this));
        Objects.requireNonNull(getCommand("naptien")).setExecutor(new NapTienCommand(this));
    }

    @Override
    public void onDisable() {
        playerMaps.keySet().forEach(playerId -> {
            Player player = getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                removeMapFromPlayer(player);
            }
        });
        playerMaps.clear();
    }

    private void createTransactionFile() {
        transactionFile = new File(getDataFolder(), "giaodich.yml");
        boolean dirsCreated = transactionFile.getParentFile().mkdirs();
        try {
            transactionFile.createNewFile();
        } catch (Exception e) {
            getLogger().severe("Could not create transaction file: " + e.getMessage());
        }
        transactionConfig = YamlConfiguration.loadConfiguration(transactionFile);
    }

    public class ATMCommand implements CommandExecutor {
        private BankAuto plugin;

        public ATMCommand(BankAuto plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            Player player = (Player) sender;

            if (args.length > 0) {
                if ("test".equals(args[0])) {
                    String baseUrl = getConfig().getString("api.url", "http://14.185.8.50:3000/check");
                    String username = getConfig().getString("api.username", "defaultUsername");
                    String password = getConfig().getString("api.password", "defaultPassword");
                    String accountNumber = getConfig().getString("api.accountNumber", "defaultAccountNumber");
                    String requestUrl = String.format("%s?username=%s&password=%s&accountNumber=%s", baseUrl, username, password, accountNumber);

                    getServer().getScheduler().runTaskAsynchronously(BankAuto.this, () -> {
                        try (CloseableHttpClient client = HttpClients.createDefault()) {
                            HttpGet request = new HttpGet(requestUrl);
                            HttpResponse response = client.execute(request);
                            String responseString = EntityUtils.toString(response.getEntity());
                            getServer().getScheduler().runTask(BankAuto.this, () -> player.sendMessage("API Response: " + responseString));
                        } catch (Exception e) {
                            getLogger().severe("Error fetching API data: " + e.getMessage());
                        }
                    });
                } else if ("reload".equals(args[0])) {
                    if (player.hasPermission("bankauto.reload")) {
                        reloadConfig();
                        player.sendMessage(ChatColor.GREEN + "Cấu hình đã được reload.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Bạn không có quyền để thực hiện lệnh này.");
                    }
                } else {
                    player.sendMessage("Usage: /atm <test|reload>");
                }
            } else {
                player.sendMessage("Usage: /atm <test|reload>");
            }
            return true;
        }
    }

    public class NapTienCommand implements CommandExecutor {
        private BankAuto plugin;

        public NapTienCommand(BankAuto plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length > 0 && args[0].equalsIgnoreCase("huy")) {
                if (plugin.activeTransactions.containsKey(player.getUniqueId())) {
                    plugin.activeTransactions.remove(player.getUniqueId());
                    plugin.removeMapFromPlayer(player);
                    player.sendMessage(ChatColor.RED + "Giao dịch đã bị hủy.");
                    return true;
                } else {
                    player.sendMessage(ChatColor.RED + "Bạn không có giao dịch nào để hủy.");
                    return true;
                }
            }

            if (plugin.activeTransactions.getOrDefault(player.getUniqueId(), false)) {
                player.sendMessage(ChatColor.RED + "Bạn đã có một giao dịch đang diễn ra.");
                return true;
            }

            plugin.activeTransactions.put(player.getUniqueId(), true);
            plugin.giveMapToPlayer(player);

            int HetHanGiaoDich = plugin.getConfig().getInt("HetHanGiaoDich", 10);
            new BukkitRunnable() {
                int counter = HetHanGiaoDich;
                public void run() {
                    if (counter <= 0) {
                        if (plugin.activeTransactions.remove(player.getUniqueId()) != null) {
                            plugin.removeMapFromPlayer(player);
                            player.sendMessage(ChatColor.RED + "Giao dịch đã bị hủy do hết thời gian.");
                        }
                        cancel();
                    }
                    counter--;
                }
            }.runTaskTimerAsynchronously(plugin, 20L, 20L);

            player.sendMessage(ChatColor.GREEN + "Giao dịch đã được bắt đầu, giao dịch sẽ bị hủy sau " + HetHanGiaoDich + " giây.");
            return true;
        }
    }

    private static final String ALPHANUMERIC_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private String generateAddInfo() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int randomIndex = random.nextInt(ALPHANUMERIC_CHARACTERS.length());
            sb.append(ALPHANUMERIC_CHARACTERS.charAt(randomIndex));
        }
        return sb.toString();
    }

    private void giveMapToPlayer(Player player) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String addInfo = generateAddInfo();
                playerAddInfoMap.put(player.getUniqueId(), addInfo);

                URL url = new URL(String.format("https://img.vietqr.io/image/%s-%s-qr_only.jpg?addInfo=%s&accountName=%s",
                        getConfig().getString("bank.name", "mbbank"),
                        getConfig().getString("bank.accountNumber", "xxxxxxxxx"),
                        addInfo,
                        getConfig().getString("bank.accountName", "xxxxxxxxx")));

                BufferedImage image = ImageIO.read(url);
                MapView map = getServer().createMap(player.getWorld());
                map.getRenderers().clear();
                map.addRenderer(new ImageMapRenderer(image, false));
                getServer().getScheduler().runTask(this, () -> {
                    ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                    MapMeta meta = (MapMeta) mapItem.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName(ChatColor.AQUA + "Nạp Tiền ATM");
                        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Không thể xóa, sử dụng, hoặc tương tác"));
                        meta.setMapView(map);
                        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                        meta.setUnbreakable(true);
                        mapItem.setItemMeta(meta);
                    }
                    player.getInventory().addItem(mapItem);
                    trackMap(player, map.getId());
                    player.sendMessage(ChatColor.GREEN + "Giao dịch đã được bắt đầu, giao dịch sẽ bị hủy sau " + getConfig().getInt("HetHanGiaoDich", 10) + " giây.");
                });
            } catch (Exception e) {
                getLogger().severe("Error processing image: " + e.getMessage());
            }
        });
    }

    private void checkTransactionHistory(Player player) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String baseUrl = getConfig().getString("api.url", "http://14.185.8.50:3000/check");
            String username = getConfig().getString("api.username", "defaultUsername");
            String password = getConfig().getString("api.password", "defaultPassword");
            String accountNumber = getConfig().getString("api.accountNumber", "defaultAccountNumber");
            String addInfo = playerAddInfoMap.getOrDefault(player.getUniqueId(), "defaultAddInfo");

            String requestUrl = String.format("%s?username=%s&password=%s&accountNumber=%s", baseUrl, username, password, accountNumber);
            HttpGet request = new HttpGet(requestUrl);
            HttpResponse response = client.execute(request);
            String jsonStr = EntityUtils.toString(response.getEntity());
            JSONObject jsonObj = new JSONObject(jsonStr);

            if (jsonObj.has("transactionHistoryList")) {
                JSONArray transactions = jsonObj.getJSONArray("transactionHistoryList");
                for (int i = 0; i < transactions.length(); i++) {
                    JSONObject transaction = transactions.getJSONObject(i);
                    String refNo = transaction.getString("refNo");
                    if (!transactionConfig.contains(refNo) && transaction.getInt("creditAmount") > 0 && addInfo.equals(transaction.getString("addDescription"))) {
                        int amount = transaction.getInt("creditAmount");
                        transactionConfig.set(refNo + ".amount", amount);
                        transactionConfig.set(refNo + ".description", transaction.getString("description"));
                        transactionConfig.set(refNo + ".date", transaction.getString("postingDate"));
                        transactionConfig.set(refNo + ".playerName", player.getName());
                        saveTransactions();

                        List<String> commands = getConfig().getStringList("commandsOnSuccess");
                        for (String cmd : commands) {
                            String processedCommand = cmd.replace("<player>", player.getName())
                                    .replace("<amount>", String.valueOf(amount));
                            getServer().dispatchCommand(getServer().getConsoleSender(), processedCommand);
                        }

                        getServer().getScheduler().runTask(this, () -> {
                            player.sendMessage(ChatColor.GREEN + "Bạn đã nạp thành công " + amount + " VND.");
                            removeMapFromPlayer(player);
                        });
                        break;
                    }
                }
            } else {
                getLogger().warning("Transaction list not found in API response.");
            }
        } catch (Exception e) {
            getLogger().severe("Error checking transaction history: " + e.getMessage());
        }
    }

    private void trackMap(Player player, int mapId) {
        playerMaps.put(player.getUniqueId(), mapId);
    }

    private void removeMapFromPlayer(Player player) {
        if (playerMaps.containsKey(player.getUniqueId())) {
            int mapId = playerMaps.get(player.getUniqueId());
            player.getInventory().forEach(item -> {
                if (item != null && item.getType() == Material.FILLED_MAP) {
                    MapMeta meta = (MapMeta) item.getItemMeta();
                    if (meta != null && meta.hasMapView() && Objects.requireNonNull(meta.getMapView()).getId() == mapId) {
                        item.setAmount(0);
                    }
                }
            });
            playerMaps.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Giao Dịch Đã Bị Hủy Di Hết Thời Gian Hoặc Đã Giao Dịch Thành Công");
        }
    }

    private void saveTransactions() {
        try {
            transactionConfig.save(transactionFile);
        } catch (Exception e) {
            getLogger().severe("Could not save transactions: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() != Material.FILLED_MAP) return;

        MapMeta meta = (MapMeta) clickedItem.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(ChatColor.AQUA + "Nạp Tiền ATM")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            player.sendMessage(ChatColor.RED + "Bạn không thể tương tác với bản đồ này.");
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.getType() == Material.FILLED_MAP) {
            MapMeta meta = (MapMeta) item.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(ChatColor.AQUA + "Nạp Tiền ATM")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Bạn không thể vứt bản đồ này.");
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        event.getDrops().removeIf(item -> item.hasItemMeta() && item.getItemMeta().hasDisplayName() && item.getItemMeta().getDisplayName().equals(ChatColor.AQUA + "Nạp Tiền ATM"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (activeTransactions.containsKey(player.getUniqueId())) {
            activeTransactions.remove(player.getUniqueId());
            removeMapFromPlayer(player);
        }
    }

    public static class ImageMapRenderer extends MapRenderer {
        private final BufferedImage image;
        private boolean done;

        public ImageMapRenderer(BufferedImage originalImage, boolean done) {
            this.image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = this.image.createGraphics();
            g2.drawImage(originalImage, 0, 0, 128, 128, null);
            g2.dispose();
            this.done = done;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (!done) {
                canvas.drawImage(0, 0, this.image);
                done = true;
            }
        }
    }
}
