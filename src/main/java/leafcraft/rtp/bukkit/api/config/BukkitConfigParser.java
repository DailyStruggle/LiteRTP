package leafcraft.rtp.bukkit.api.config;

import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.substitutions.RTPLocation;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.api.substitutions.BukkitRTPWorld;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

public class BukkitConfigParser<E extends Enum<E>> extends ConfigParser<E> {
    private YamlConfiguration configuration;

    public BukkitConfigParser(Class<E> eClass, String name, String version, File pluginDirectory) {
        super(eClass, name, version, pluginDirectory);
    }

    public BukkitConfigParser(Class<E> eClass, String name, String version, File pluginDirectory, File langFile) {
        super(eClass, name, version, pluginDirectory, langFile);
    }

    @Override
    public void saveResource(String name, boolean overwrite) {
        String myDirectory = pluginDirectory.getAbsolutePath();
        String pDirectory = RTPBukkitPlugin.getInstance().getDataFolder().getAbsolutePath();
        if(myDirectory.equals(pDirectory)) {
            RTPBukkitPlugin.getInstance().saveResource(name,overwrite);
        }
        else {
            String diff = myDirectory.substring(pDirectory.length()+1);
            if(name.equals("default.yml")) {
                RTPBukkitPlugin.getInstance().saveResource(diff + File.separator + name, false);
            }
            else {
                File source = new File(myDirectory + File.separator + "default.yml");
                File target = new File(myDirectory + File.separator + name);
                FileUtil.copy(source, target);
            }
        }
    }

    @Override
    public void loadResource(File f) {
        configuration = YamlConfiguration.loadConfiguration(f);
        data = new EnumMap<>(myClass);
        for(E key : myClass.getEnumConstants()) {
            Object res = configuration.get(key.name());
            data.put(key,res);
        }
    }

    @Override
    protected Object getFromString(String val, @Nullable Object def) {
        Object res;
        Object o = configuration.get(val, def);

        if(o instanceof ConfigurationSection section) {
            res = getSectionRecursive(section);
        } else if(o instanceof Location location) {
            res = new RTPLocation(
                    new BukkitRTPWorld(location.getWorld()),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        } else if(o instanceof Color color) {
            res = color.asRGB();
        } //todo: item stacks
        else res = o;
        return res;
    }

    @Override
    public void set(@NotNull E key, @NotNull Object o) {
        super.set(key,o);
        configuration.set(key.name(),o);
    }

    protected Map<String,Object> getSectionRecursive(ConfigurationSection section) {
        Map<String,Object> res = new HashMap<>();
        Set<String> keys = section.getKeys(false);
        for(String key : keys) {
            Object val;
            Object o = section.get(key);
            if(o instanceof ConfigurationSection configurationSection) {
                val = getSectionRecursive(configurationSection);
            } else if(o instanceof Location location) {
                val = new RTPLocation(
                        new BukkitRTPWorld(location.getWorld()),
                        location.getBlockX(),
                        location.getBlockY(),
                        location.getBlockZ()
                );
            } else if(o instanceof Color color) {
                val = color.asRGB();
            } else {
                val = section.get(key);
            } //todo: item stacks
            res.put(key,val);
        }
        return res;
    }

    //todo: clone function??

    //todo: write resultant updates?
    //todo: for factory types, map name key to correct factory
    //          + construct from given name and try the values
    //          + remove unused config keys

}
