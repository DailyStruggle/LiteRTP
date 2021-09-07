package leafcraft.rtp.tools;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Cache {
    private final RTP plugin;
    private final Configs configs;

    public ConcurrentHashMap<RandomSelectParams,BukkitTask> queueTimers = new ConcurrentHashMap<>();

    public Cache(RTP plugin, Configs configs) {
        this.plugin = plugin;
        this.configs = configs;

        fetchPlayerData();

        for(String region : configs.regions.getRegionNames()) {
            String worldName = (String) configs.regions.getRegionSetting(region,"world","world");
            World world = Bukkit.getWorld(worldName);
            if(world == null) world = Bukkit.getWorlds().get(0);
            Map<String,String> map = new HashMap<>();
            map.put("region",region);
            RandomSelectParams key = new RandomSelectParams(world,map,configs);
            TeleportRegion teleportRegion = new TeleportRegion(region,key.params,configs,this);
            permRegions.put(key, teleportRegion);
            teleportRegion.loadFile();
        }

        Double i = 0d;
        Integer period = configs.config.queuePeriod;
        if(period > 0) {
            Double increment = ((period.doubleValue()) / permRegions.size()) * 20;
            for (Map.Entry<RandomSelectParams, TeleportRegion> entry : permRegions.entrySet()) {
                queueTimers.put(entry.getKey(), Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                    double tps = TPS.getTPS();
                    double minTps = (Double) configs.config.getConfigValue("minTPS", 19.0);
                    if (tps < minTps) return;
                    QueueLocation queueLocation = new QueueLocation(entry.getValue(), this);
                    queueLocationTasks.put(queueLocation.idx, queueLocation);
                    queueLocation.runTaskAsynchronously(plugin);
                }, 200 + i.intValue(), period * 20));
                i += increment;
            }
        }
    }

    //if we needed to force load a chunk to prevent unload, undo that on teleport.
    public ConcurrentHashMap<HashableChunk,Long> forceLoadedChunks = new ConcurrentHashMap<>();

    //table of which players are teleporting to what location
    // key: player name
    // value: location they're going to, to be re-added to the queue on cancellation
    public ConcurrentHashMap<UUID,CommandSender> commandSenderLookup = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID,Location> todoTP = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID,Location> lastTP = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID,RandomSelectParams> regionKeys = new ConcurrentHashMap<>();

    //Bukkit task list in case of cancellation
    public ConcurrentHashMap<UUID, SetupTeleport> setupTeleports = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID, LoadChunks> loadChunks = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID, DoTeleport> doTeleports = new ConcurrentHashMap<>();
    public ConcurrentHashMap<Long, QueueLocation> queueLocationTasks = new ConcurrentHashMap<>();

    //pre-teleport location info for checking distance from command location
    public ConcurrentHashMap<UUID, Location> playerFromLocations = new ConcurrentHashMap<>();

    //info on number of attempts on last rtp command
    public ConcurrentHashMap<Location, Integer> numTeleportAttempts = new ConcurrentHashMap<>();

    //store teleport command cooldown
    public ConcurrentHashMap<UUID,Long> lastTeleportTime = new ConcurrentHashMap<>();
    public ConcurrentHashMap<UUID,Double> currentTeleportCost = new ConcurrentHashMap<>();

    public ConcurrentHashMap<RandomSelectParams, TeleportRegion> tempRegions = new ConcurrentHashMap<>();
    public ConcurrentHashMap<RandomSelectParams, TeleportRegion> permRegions = new ConcurrentHashMap<>();

    public ConcurrentHashMap<UUID,Player> invulnerablePlayers = new ConcurrentHashMap<>();

    public void shutdown() {
        for(Player player : invulnerablePlayers.values()) {
            player.setInvulnerable(false);
        }

        for(ConcurrentHashMap.Entry<UUID,SetupTeleport> entry : setupTeleports.entrySet()) {
            entry.getValue().cancel();
        }
        setupTeleports.clear();

        for(ConcurrentHashMap.Entry<UUID,LoadChunks> entry : loadChunks.entrySet()) {
            entry.getValue().cancel();
        }
        loadChunks.clear();

        for(ConcurrentHashMap.Entry<UUID,DoTeleport> entry : doTeleports.entrySet()) {
            entry.getValue().cancel();
        }
        doTeleports.clear();

        for(ConcurrentHashMap.Entry<Long,QueueLocation> entry : queueLocationTasks.entrySet()) {
            entry.getValue().cancel();
        }
        queueLocationTasks.clear();

        for(TeleportRegion region : tempRegions.values()) {
            region.shutdown();
        }

        for(TeleportRegion region : permRegions.values()) {
            region.shutdown();
            region.storeFile();
        }

        for(Map.Entry<HashableChunk,Long> entry : forceLoadedChunks.entrySet()) {
            entry.getKey().getChunk().setForceLoaded(false);
        }
        forceLoadedChunks.clear();

        for(Map.Entry<RandomSelectParams,BukkitTask> entry : queueTimers.entrySet()) {
            entry.getValue().cancel();
            queueTimers.remove(entry);
        }

        storePlayerData();
    }

    public Location getQueuedLocation(RandomSelectParams rsParams, CommandSender sender, Player player) {
        TeleportRegion region;
        Double price = 0d;
        boolean didWithdraw = (sender instanceof Player) && currentTeleportCost.containsKey(((Player)sender).getUniqueId());
        if(permRegions.containsKey(rsParams)) {
            region = permRegions.get(rsParams);

            if(!sender.hasPermission("rtp.free") && !didWithdraw) {
                price = (Double) configs.regions.getRegionSetting(region.name, "price", 0.0);
            }
        }
        else return null;

        Economy economy = VaultChecker.getEconomy();
        if(price > 0 && sender instanceof Player && economy!=null) {
            boolean canPay = economy.has((Player)sender,price);
            if(canPay) {
                economy.withdrawPlayer((Player)sender,price);
                currentTeleportCost.put(((Player)sender).getUniqueId(),price);
            }
            else {
                String msg = configs.lang.getLog("notEnoughMoney", price.toString());
                SendMessage.sendMessage(sender,player,msg);
                return null;
            }
        }

        return region.getQueuedLocation(sender, player);
    }

    public Location getRandomLocation(RandomSelectParams rsParams, boolean urgent, CommandSender sender, Player player) {
        TeleportRegion region;
        Double price = 0d;
        boolean didWithdraw = (sender instanceof Player) && currentTeleportCost.containsKey(((Player)sender).getUniqueId());
        if(permRegions.containsKey(rsParams)) {
            region = permRegions.get(rsParams);
            if(!sender.hasPermission("rtp.free") && !didWithdraw) {
                price = (Double) configs.regions.getRegionSetting(region.name, "price", 0.0);
            }
        }
        else {
            region = new TeleportRegion("temp", rsParams.params, configs, this);
            if(!sender.hasPermission("rtp.free") && !didWithdraw) {
                price = configs.config.price;
            }
            tempRegions.put(rsParams,region);
        }

        Economy economy = VaultChecker.getEconomy();
        if(price > 0 && sender instanceof Player && economy!=null) {
            boolean canPay = economy.has((Player)sender,price);
            if(canPay) {
                economy.withdrawPlayer((Player)sender,price);
                currentTeleportCost.put(((Player)sender).getUniqueId(),price);
            }
            else {
                String msg = configs.lang.getLog("notEnoughMoney", price.toString());
                SendMessage.sendMessage(sender,player,msg);
                return null;
            }
        }

        Location res;
        if(rsParams.params.containsKey("biome")) {
            res = region.getLocation(sender,player,Biome.valueOf(rsParams.params.get("biome")));
        }
        else res = region.getLocation(urgent, sender, player);
        return res;
    }

    public void resetRegions() {
        for(TeleportRegion region : tempRegions.values()) {
            region.shutdown();
        }
        tempRegions.clear();

        for(TeleportRegion region : permRegions.values()) {
            region.storeFile();
            region.shutdown();
        }
        permRegions.clear();

        for(Map.Entry<HashableChunk,Long> entry : forceLoadedChunks.entrySet()) {
            entry.getKey().getChunk().setForceLoaded(false);
            entry.getKey().getChunk().unload(true);
        }
        forceLoadedChunks.clear();

        for(String region : configs.regions.getRegionNames()) {
            String worldName = (String) configs.regions.getRegionSetting(region,"world","world");
            World world = Bukkit.getWorld(worldName);
            if(world == null) world = Bukkit.getWorlds().get(0);
            Map<String,String> map = new HashMap<>();
            map.put("region",region);
            RandomSelectParams key = new RandomSelectParams(world,map,configs);
            TeleportRegion teleportRegion = new TeleportRegion(region, key.params,configs,this);
            permRegions.put(key, teleportRegion);
            teleportRegion.loadFile();
        }

        Double i = 0d;
        Integer period = configs.config.queuePeriod;
        if(period > 0) {
            Double increment = ((period.doubleValue()) / permRegions.size()) * 20;
            for (Map.Entry<RandomSelectParams, TeleportRegion> entry : permRegions.entrySet()) {
                queueTimers.put(entry.getKey(), Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                    double tps = TPS.getTPS();
                    double minTps = (Double) configs.config.getConfigValue("minTPS", 19.0);
                    if (tps < minTps) return;
                    QueueLocation queueLocation = new QueueLocation(entry.getValue(), this);
                    queueLocationTasks.put(queueLocation.idx, queueLocation);
                    queueLocation.runTaskAsynchronously(plugin);
                }, 40 + i.intValue(), period * 20));
                i += increment;
            }
        }
    }

    public void fetchPlayerData() {
        File playerFile = new File(plugin.getDataFolder(),"playerCooldowns.dat");
        if(!playerFile.exists()) {
            return;
        }
        long time = System.nanoTime();
        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        for(String id : config.getKeys(false)) {
            lastTeleportTime.put(UUID.fromString(id),time-TimeUnit.MILLISECONDS.toNanos(config.getLong(id)));
        }
    }

    public void storePlayerData() {
        File playerFile = new File(plugin.getDataFolder(),"playerCooldowns.dat");
        if(!playerFile.exists()) {
            try {
                playerFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
        long time = System.nanoTime();
        for(Map.Entry<UUID,Long> entry : lastTeleportTime.entrySet()) {
            config.set(entry.getKey().toString(),TimeUnit.NANOSECONDS.toMillis(time-entry.getValue()));
        }
        try {
            config.save(playerFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
