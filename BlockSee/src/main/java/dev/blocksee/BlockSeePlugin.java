package dev.blocksee;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BlockSeePlugin extends JavaPlugin implements Listener {

    private final Set<UUID> inspecting = new HashSet<>();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlockSee-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    @Override
    public void onEnable() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new SQLException("Could not create plugin data folder");
            }
            File dbFile = new File(getDataFolder(), "blocks.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS placed_blocks (
                            world TEXT NOT NULL,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            player_uuid TEXT NOT NULL,
                            player_name TEXT NOT NULL,
                            material TEXT NOT NULL,
                            placed_at INTEGER NOT NULL,
                            PRIMARY KEY (world, x, y, z)
                        )
                        """);
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BlockSee enabled.");
    }

    @Override
    public void onDisable() {
        dbExecutor.shutdown();
        try {
            dbExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("see")) {
            if (inspecting.remove(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + "Block inspector " + ChatColor.RED + "disabled" + ChatColor.YELLOW + ".");
            } else {
                inspecting.add(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW + "Block inspector " + ChatColor.GREEN + "enabled" + ChatColor.YELLOW
                        + ". Click any block to see who placed it. Run /block see again to turn it off.");
            }
            return true;
        }
        player.sendMessage(ChatColor.RED + "Usage: /block see");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("see");
        }
        return List.of();
    }

    // ----------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!inspecting.contains(player.getUniqueId())) {
            return;
        }
        // Main hand only, so right-click doesn't fire twice
        if (event.getHand() != null && event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        event.setCancelled(true);

        final String world = block.getWorld().getName();
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        final String type = block.getType().name();

        dbExecutor.execute(() -> {
            String name = null;
            long placedAt = 0;
            String material = null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT player_name, material, placed_at FROM placed_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        name = rs.getString(1);
                        material = rs.getString(2);
                        placedAt = rs.getLong(3);
                    }
                }
            } catch (SQLException e) {
                getLogger().warning("Lookup failed: " + e.getMessage());
            }

            final String fName = name;
            final String fMaterial = material;
            final long fPlacedAt = placedAt;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                String header = ChatColor.GOLD + "[BlockSee] " + ChatColor.GRAY + type + " at " + x + ", " + y + ", " + z;
                player.sendMessage(header);
                if (fName == null) {
                    player.sendMessage(ChatColor.YELLOW + "No placement record (placed before the plugin, by the world, or removed).");
                } else {
                    String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(fPlacedAt));
                    player.sendMessage(ChatColor.YELLOW + "Placed by " + ChatColor.WHITE + fName
                            + ChatColor.YELLOW + " on " + ChatColor.WHITE + when
                            + ChatColor.GRAY + " (" + fMaterial + ")");
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();
        final String world = block.getWorld().getName();
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        final String uuid = player.getUniqueId().toString();
        final String name = player.getName();
        final String material = block.getType().name();
        final long now = System.currentTimeMillis();

        dbExecutor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO placed_blocks (world,x,y,z,player_uuid,player_name,material,placed_at) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, uuid);
                ps.setString(6, name);
                ps.setString(7, material);
                ps.setLong(8, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Could not log placement: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        removeRecord(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::removeRecord);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::removeRecord);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inspecting.remove(event.getPlayer().getUniqueId());
    }

    private void removeRecord(Block block) {
        final String world = block.getWorld().getName();
        final int x = block.getX(), y = block.getY(), z = block.getZ();
        dbExecutor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM placed_blocks WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Could not remove record: " + e.getMessage());
            }
        });
    }
}
