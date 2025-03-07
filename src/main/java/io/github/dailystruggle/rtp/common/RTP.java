package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPEconomy;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tools.ChunkyChecker;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * class to hold relevant API functions, outside of Bukkit functionality
 */
public class RTP {
    public static final ConcurrentLinkedQueue<CompletableFuture<?>> futures = new ConcurrentLinkedQueue<>();

    public static final SelectionAPI selectionAPI = new SelectionAPI();

    public static EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>( factoryNames.class );

    /**
     * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking during lag spikes
     */
    public static int minRTPExecutions = 1;
    /**
     * only one of each of these objects
     */
    public static Configs configs;
    public static RTPServerAccessor serverAccessor;
    public static RTPEconomy economy = null;
    public static TreeCommand baseCommand;
    public static AtomicBoolean reloading = new AtomicBoolean( false );
    /**
     * only one instance will exist at a time, reset on plugin load
     */
    private static RTP instance;

    static {
        Factory<Shape<?>> shapeFactory = new Factory<>();
        factoryMap.put( factoryNames.shape, shapeFactory );

        Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
        factoryMap.put( factoryNames.vert, verticalAdjustorFactory );
        factoryMap.put( factoryNames.singleConfig, new Factory<ConfigParser<?>>() );
        factoryMap.put( factoryNames.multiConfig, new Factory<MultiConfigParser<?>>() );
    }

    public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();
    public final RTPTaskPipe setupTeleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe getChunkPipeline = new RTPTaskPipe();
    public final RTPTaskPipe loadChunksPipeline = new RTPTaskPipe();
    public final RTPTaskPipe teleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe chunkCleanupPipeline = new RTPTaskPipe();
    public final RTPTaskPipe miscSyncTasks = new RTPTaskPipe();
    public final RTPTaskPipe miscAsyncTasks = new RTPTaskPipe();
    public final RTPTaskPipe startupTasks = new RTPTaskPipe();
    public final RTPTaskPipe cancelTasks = new RTPTaskPipe();
    public final Map<String, FillTask> fillTasks = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, Long> invulnerablePlayers = new ConcurrentHashMap<>();
    public DatabaseAccessor<?> databaseAccessor;
    public RTP() {
        if ( serverAccessor == null ) throw new IllegalStateException( "null serverAccessor" );
        instance = this;

        RTPAPI.addShape( new Circle() );
        RTPAPI.addShape( new Square() );
        RTPAPI.addShape( new Rectangle() );
        RTPAPI.addShape( new Circle_Normal() );
        RTPAPI.addShape( new Square_Normal() );
        new LinearAdjustor( new ArrayList<>() ); //todo: make this work
        new JumpAdjustor( new ArrayList<>() );

        configs = new Configs( serverAccessor.getPluginDirectory() );

        ChunkyChecker.loadChunky();
    }

    public static RTP getInstance() {
        return instance;
    }

    public static void log( Level level, String str ) {
        serverAccessor.log( level, str );
    }

    public static void log( Level level, String str, Throwable throwable ) {
        serverAccessor.log( level, str, throwable );
    }

    public static RTPWorld getWorld( RTPPlayer player ) {
        //get region from world name, check for overrides
        Set<String> worldsAttempted = new HashSet<>();
        String worldName = player.getLocation().world().name();
        MultiConfigParser<WorldKeys> worldParsers = ( MultiConfigParser<WorldKeys> ) RTP.configs.multiConfigParserMap.get( WorldKeys.class );
        ConfigParser<WorldKeys> worldParser = worldParsers.getParser( worldName );
        boolean requirePermission = Boolean.parseBoolean( worldParser.getConfigValue( WorldKeys.requirePermission, false ).toString() );

        while ( requirePermission && !player.hasPermission( "rtp.worlds." + worldName) ) {
            if ( worldsAttempted.contains( worldName) )
                throw new IllegalStateException( "infinite override loop detected at world - " + worldName );
            worldsAttempted.add( worldName );

            worldName = String.valueOf( worldParser.getConfigValue( WorldKeys.override, "DEFAULT.YML") ).toUpperCase();
            if( !worldName.equals( ".YML") ) worldName = worldName + ".YML";
            worldParser = worldParsers.getParser( worldName );
            requirePermission = Boolean.parseBoolean( worldParser.getConfigValue( WorldKeys.requirePermission, false ).toString() );
        }

        return serverAccessor.getRTPWorld( worldName );
    }

    public static void stop() {
        List<CompletableFuture<?>> validFutures = new ArrayList<>( futures.size() );
        for( CompletableFuture<?> future : futures ) {
            if( !future.isDone() ) validFutures.add( future );
        }
        if(!validFutures.isEmpty()) {
            for ( CompletableFuture<?> future : validFutures ) {
                try {
                    if ( future.isDone() ) continue;
                    future.complete( null );
                } catch ( CancellationException ignored ) {

                }
            }
        }

        if ( instance == null ) return;

        for ( Map.Entry<UUID, TeleportData> e : instance.latestTeleportData.entrySet() ) {
            TeleportData data = e.getValue();
            if ( data == null || data.completed ) continue;
            new RTPTeleportCancel( e.getKey() ).run();
        }

        instance.databaseAccessor.stop.set( true );

        instance.chunkCleanupPipeline.stop();
        instance.miscAsyncTasks.stop();
        instance.miscSyncTasks.stop();
        instance.setupTeleportPipeline.stop();
        instance.loadChunksPipeline.stop();
        instance.teleportPipeline.stop();

        for ( Region r : selectionAPI.permRegionLookup.values() ) {
            r.shutDown();
        }
        selectionAPI.permRegionLookup.clear();

        for ( Region r : selectionAPI.tempRegions.values() ) {
            r.shutDown();
        }
        selectionAPI.tempRegions.clear();

        instance.latestTeleportData.forEach( (uuid, data ) -> {
            if ( !data.completed ) new RTPTeleportCancel( uuid ).run();
        } );

        instance.processingPlayers.clear();

        FillTask.kill();

        serverAccessor.stop();
    }

    /**
     * dynamic factories for certain types
     */
    public enum factoryNames {
        shape,
        vert,
        singleConfig,
        multiConfig
    }
}
