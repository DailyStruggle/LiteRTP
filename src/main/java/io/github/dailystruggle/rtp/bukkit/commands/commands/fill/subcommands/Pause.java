package io.github.dailystruggle.rtp.bukkit.commands.commands.fill.subcommands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.commands.commands.BaseRTPCmd;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

public class Pause extends BaseRTPCmd {
    public Pause(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
//        FillCmd.apply(sender,parameterValues, teleportRegion -> !teleportRegion.isFilling(), "fillNotRunning", MemoryRegion::pauseFill);
        return true;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String permission() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }
}
