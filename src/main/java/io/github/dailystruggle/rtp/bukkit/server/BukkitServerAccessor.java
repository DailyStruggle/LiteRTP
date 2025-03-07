package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BukkitServerAccessor implements RTPServerAccessor {
    private final Map<UUID, RTPWorld> worldMap = new ConcurrentHashMap<>();
    private final Map<String, RTPWorld> worldMapStr = new ConcurrentHashMap<>();
    Function<String, Shape<?>> shapeFunction;
    private String version = null;
    private Integer intVersion = null;
    private Function<RTPWorld,Set<String>> biomes = BukkitRTPWorld::getBiomes;

    private Function<String, WorldBorder> worldBorderFunction = s -> {
        RTPWorld rtpWorld = getRTPWorld( s );
        if ( rtpWorld instanceof BukkitRTPWorld ) {
            World world = ( (BukkitRTPWorld ) rtpWorld ).world();
            org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
            return new WorldBorder(
                    () -> {
                        Shape<?> shape = RTP.serverAccessor.getShape(s);
                        if( !shape.name.equalsIgnoreCase("SQUARE") )
                            shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
                        Square square = (Square) shape;
                        square.set(GenericMemoryShapeParams.radius, ((long) worldBorder.getSize()*0.9) / 32);
                        square.set(GenericMemoryShapeParams.centerRadius, 0L);
                        square.set(GenericMemoryShapeParams.centerX,worldBorder.getCenter().getBlockX()/16);
                        square.set(GenericMemoryShapeParams.centerZ,worldBorder.getCenter().getBlockZ()/16);
                        square.set(GenericMemoryShapeParams.expand,false);
                        square.set(GenericMemoryShapeParams.weight,1);
                        square.set(GenericMemoryShapeParams.mode, Mode.NEAREST);
                        square.set(GenericMemoryShapeParams.uniquePlacements,false);
                        return shape;
                    },
                    rtpLocation -> {
                        if ( RTP.serverAccessor.getServerIntVersion() > 10 )
                            return worldBorder.isInside( new Location( world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z()) );
                        Location center = worldBorder.getCenter();
                        double radius = worldBorder.getSize() / 2;
                        RTPLocation c = new RTPLocation( rtpWorld, center.getBlockX(), center.getBlockY(), center.getBlockZ() );
                        return c.distanceSquaredXZ( rtpLocation ) < Math.pow( radius, 2 );
                    } );
        }
        return null;
    };

    public BukkitServerAccessor() {
        //run later to ensure RTP instance exists
        // configs are initialized in tick 1, so reference them at 2 or later
        // command processing timer is delayed to ensure this is set up before it's used

        shapeFunction = s -> {
            World world = Bukkit.getWorld( s );
            if ( world == null ) return null;
            Region region = RTP.selectionAPI.getRegion( getRTPWorld( world.getUID()) );
            if ( region == null ) throw new IllegalStateException();
            Object o = region.getData( RegionKeys.shape );
            if ( !(o instanceof Shape<?>) ) throw new IllegalStateException();
            return ( Shape<?> ) o;
        };
    }

    private static final Pattern versionPattern = Pattern.compile( "[-+^.a-zA-Z]*",Pattern.CASE_INSENSITIVE );
    @Override
    public @NotNull String getServerVersion() {
        if ( version == null ) {
            version = RTPBukkitPlugin.getInstance().getServer().getClass().getPackage().getName();
            if(!version.contains("1_")) {
                String bukkitVersion = RTPBukkitPlugin.getInstance().getServer().getBukkitVersion();

                int end = bukkitVersion.indexOf("-R");
                if(end < 0) return "1_13_2";

                bukkitVersion = bukkitVersion.substring(0,end).replaceAll("\\.","_");
                return bukkitVersion;
            }
            else version = versionPattern.matcher( version ).replaceAll( "" );
        }

        return version;
    }

    @Override
    public @NotNull Integer getServerIntVersion() {
        if ( intVersion == null ) {
            String[] splitVersion = getServerVersion().split( "_" );
            if ( splitVersion.length == 0 ) {
                intVersion = 1;
            } else if ( splitVersion.length == 1 ) {
                try {
                    intVersion = Integer.valueOf( splitVersion[0] );
                } catch (NumberFormatException e) {
                    RTP.log(Level.WARNING,"expected number, received - " + splitVersion[0],e);
                    intVersion = 1;
                }
            } else {
                try {
                    intVersion = Integer.valueOf( splitVersion[1] );
                } catch (NumberFormatException e) {
                    RTP.log(Level.WARNING,"expected number, received - " + splitVersion[1],e);
                    intVersion = 1;
                }
            }
        }
        return intVersion;
    }

    @Override
    public RTPWorld getRTPWorld( String name ) {
        RTPWorld world = worldMapStr.get( name );
        World bukkitWorld = Bukkit.getWorld( name );
        if ( world == null && bukkitWorld !=null ) {
            world = new BukkitRTPWorld(bukkitWorld);
            worldMap.put( world.id(), world );
            worldMapStr.put( world.name(), world );
        }
        if(bukkitWorld == null && world!=null) {
            worldMap.remove( world.id() );
            worldMapStr.remove(world.name());
            return null;
        }
        return world;
    }

    @Override
    public @Nullable RTPWorld getRTPWorld( UUID id ) {
        RTPWorld world = worldMap.get( id );
        World bukkitWorld = Bukkit.getWorld(id);
        if ( world == null && bukkitWorld !=null ) {
            world = new BukkitRTPWorld(bukkitWorld);
            worldMap.put( world.id(), world );
            worldMapStr.put( world.name(), world );
        }
        if(bukkitWorld == null && world!=null) {
            worldMap.remove( world.id() );
            worldMapStr.remove(world.name());
            return null;
        }
        return world;
    }

    @Override
    public @Nullable Shape<?> getShape( String name ) {
        return shapeFunction.apply( name );
    }

    @Override
    public boolean setShapeFunction( Function<String, Shape<?>> shapeFunction ) {
        boolean works = true;
        for ( World world : Bukkit.getWorlds() ) {
            try {
                Shape<?> shape = shapeFunction.apply( world.getName() );
                shape.select();
            } catch ( Exception exception ) {
                works = false;
                break;
            }
        }
        if ( works ) {
            this.shapeFunction = shapeFunction;
        }

        return works;
    }

    @Override
    public @NotNull List<RTPWorld> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map( world -> getRTPWorld( world.getUID()) ).filter( Objects::nonNull ).collect( Collectors.toList() );
    }

    @Override
    public @Nullable RTPPlayer getPlayer( UUID uuid ) {
        Player player = Bukkit.getPlayer( uuid );
        if ( player == null ) return null;
        return new BukkitRTPPlayer( player );
    }

    @Override
    public @Nullable RTPPlayer getPlayer( String name ) {
        Player player = Bukkit.getPlayer( name );
        if ( player == null ) return null;
        return new BukkitRTPPlayer( player );
    }

    @Override
    public @Nullable RTPCommandSender getSender( UUID uuid ) {
        CommandSender commandSender = ( uuid == CommandsAPI.serverId ) ? Bukkit.getConsoleSender() : Bukkit.getPlayer( uuid );
        if ( commandSender == null ) return null;
        if ( commandSender instanceof Player ) return new BukkitRTPPlayer( (Player ) commandSender );
        return new BukkitRTPCommandSender( commandSender );
    }

    @Override
    public long overTime() {
        return 0;
    }

    @Override
    public File getPluginDirectory() {
        return RTPBukkitPlugin.getInstance().getDataFolder();
    }

    @Override
    public void sendMessage( UUID target, MessagesKeys msgType ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        ConfigParser<MessagesKeys> parser = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        if ( parser == null ) return;
        String msg = String.valueOf( parser.getConfigValue( msgType, "") );
        if ( msg == null || msg.isEmpty() ) return;
        sendMessage( target, msg );
    }

    @Override
    public void sendMessage( UUID target1, UUID target2, MessagesKeys msgType ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        ConfigParser<MessagesKeys> parser = ( ConfigParser<MessagesKeys> ) RTP.configs.getParser( MessagesKeys.class );
        String msg = String.valueOf( parser.getConfigValue( msgType, "") );
        if ( msg == null || msg.isEmpty() ) return;
        sendMessage( target1, target2, msg );
    }

    @Override
    public void sendMessage( UUID target, String message ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        CommandSender sender = ( target.equals( CommandsAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target );
        if ( sender != null ) SendMessage.sendMessage( sender, message );
    }

    @Override
    public void sendMessageAndSuggest( UUID target, String message, String suggestion ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        SendMessage.sendMessage( getSender( target ), message, suggestion, suggestion );
    }

    @Override
    public void sendMessage( UUID target1, UUID target2, String message ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        CommandSender sender = ( target1.equals( CommandsAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target1 );
        CommandSender player = ( target2.equals( CommandsAPI.serverId) )
                ? Bukkit.getConsoleSender()
                : Bukkit.getPlayer( target2 );

        if ( sender != null && player != null ) SendMessage.sendMessage( sender, player, message );
    }

    @Override
    public void log( Level level, String msg ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        SendMessage.log( level, msg );
    }

    @Override
    public void log( Level level, String msg, Throwable throwable ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        SendMessage.log( level, msg, throwable );
    }

    @Override
    public void announce( String msg, String permission ) {
        if ( RTPBukkitPlugin.getInstance() == null || !RTPBukkitPlugin.getInstance().isEnabled() ) return;
        SendMessage.sendMessage( Bukkit.getConsoleSender(), msg );
        for ( Player p : Bukkit.getOnlinePlayers().stream().filter( player -> player.hasPermission( permission) ).collect( Collectors.toSet()) ) {
            SendMessage.sendMessage( p, msg );
        }
    }

    @Override
    public Set<String> getBiomes( RTPWorld rtpWorld ) {
        return biomes.apply( rtpWorld );
    }

    public void setBiomes( Function<RTPWorld,Set<String>> biomes ) {
        try {
            biomes.apply( getRTPWorlds().get( 0) );
        } catch ( Exception exception ) {
            exception.printStackTrace();
            return;
        }
        this.biomes = biomes;
    }

    @Override
    public boolean isPrimaryThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public @Nullable WorldBorder getWorldBorder( String worldName ) {
        return worldBorderFunction.apply( worldName );
    }

    @Override
    public boolean setWorldBorderFunction( Function<String, WorldBorder> function ) {
        try {
            for ( RTPWorld world : getRTPWorlds() ) {
                WorldBorder border = function.apply( getRTPWorlds().get( 0 ).name() );
                int[] select = border.getShape().get().select();
                border.isInside().apply( new RTPLocation( world, select[0], 92, select[1]) );
            }
            worldBorderFunction = function;
        } catch ( Error | Exception ignored ) {
            return false;
        }

        return true;
    }

    @Override
    public Set<String> materials() {
        return Arrays.stream( Material.values() ).map( Enum::name ).collect( Collectors.toSet() );
    }

    @Override
    public void stop() {
        getRTPWorlds().forEach( RTPWorld::forgetChunks );
        RTP.getInstance().databaseAccessor.stop.set( true );

        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
        plugin.commandTimer.cancel();
        plugin.syncTimer.cancel();
        plugin.asyncTimer.cancel();

        for( RTPWorld world : worldMap.values() ) {
            world.forgetChunks();
        }

        worldMap.clear();
        worldMapStr.clear();
    }

    @Override
    public void start() {
        RTPBukkitPlugin plugin = RTPBukkitPlugin.getInstance();
        RTP.getInstance().databaseAccessor.stop.set( false );

        plugin.commandTimer = Bukkit.getScheduler().runTaskTimerAsynchronously( plugin, () -> {
            long avgTime = TPS.timeSinceTick( 20 ) / 20;
            long currTime = TPS.timeSinceTick( 1 );
            CommandsAPI.execute( avgTime - currTime );
        }, 40, 1 );

        plugin.syncTimer = Bukkit.getScheduler().runTaskTimer( plugin, () -> {
            new SyncTeleportProcessing().run();
        }, 20, 1 );
        plugin.asyncTimer = Bukkit.getScheduler().runTaskTimer( plugin, () -> {
            new AsyncTeleportProcessing().run();
        }, 20, 1 );
        plugin.fillTimer = Bukkit.getScheduler().runTaskTimer( plugin, () -> {
            new FillTaskProcessing().run();
        }, 25, 20 );
        plugin.databaseTimer = Bukkit.getScheduler().runTaskTimer( plugin, () -> {
            new DatabaseProcessing().run();
        }, 30, 20 );

        BukkitTask task = Bukkit.getScheduler().runTask( plugin, this::getRTPWorlds );
    }
}
