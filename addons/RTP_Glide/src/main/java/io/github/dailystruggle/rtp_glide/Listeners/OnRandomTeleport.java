package io.github.dailystruggle.rtp_glide.Listeners;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

public final class OnRandomTeleport implements Listener {
    private final RTP_Glide plugin;

    public OnRandomTeleport(RTP_Glide plugin) {

        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRandomTeleport(RandomTeleportEvent event) {
        if(Objects.requireNonNull(event.getTo().getWorld())
                .getEnvironment().equals(World.Environment.NETHER))
            return;
        event.getTo().setY(event.getTo().getY()+100);
        plugin.getGlidingPlayers().add(event.getPlayer().getUniqueId());
        event.getPlayer().setFlying(false);
        event.getPlayer().setGliding(true);
    }
}
