package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.file.YamlFile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * a "database" that's just reading and writing yaml files,
 *      using SimpleYaml (a server independent yaml library that can preserve comments)
 */
public class YamlFileDatabase extends DatabaseAccessor<Map<String,YamlFile>> {
    private final File directory;

    /**
     * constructor
     *
     * provide directory where yml/yaml files should exist
     */
    public YamlFileDatabase(File directory) {
        this.directory = directory;
    }

    /**
     * container for map reference access, such set() will propagate changes where == would not
     */
    public final AtomicReference<Map<String, YamlFile>> cachedLookup = new AtomicReference<>();
    {
        cachedLookup.set(new ConcurrentHashMap<>());
    }

    @Override
    public String name() {
        return "YAML";
    }

    @Override
    @NotNull
    public Map<String, YamlFile> connect() {
        if(!directory.exists()) {
            if(!directory.mkdirs()) throw new IllegalStateException("unable to create directory " + directory.getAbsolutePath());
        }

        File[] files = directory.listFiles();
        Map<String,YamlFile> res = new HashMap<>();
        if(files == null) return res;
        for (File file : files) {
            if (!file.isFile()) continue;

            YamlFile yamlFile;
            try {
                yamlFile = new YamlFile(file);
                yamlFile.loadWithComments();
            } catch (Exception exception) { //not a yaml file
                continue;
            }
            res.put(file.getName(),yamlFile);
            localTables.putIfAbsent(file.getName(),new ConcurrentHashMap<>());
            Map<TableObj, TableObj> map = localTables.get(file.getName());
            Map<String, Object> values = yamlFile.getMapValues(true);
            for(Map.Entry<String,Object> entry : values.entrySet()) {
                map.put(new TableObj(entry.getKey()),new TableObj(entry.getValue()));
            }
        }
        cachedLookup.get().clear();
        cachedLookup.get().putAll(res);
        return res;
    }

    @Override
    public void disconnect(Map<String, YamlFile> database) {
        for (YamlFile file : database.values()) {
            try {
                file.save();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public @NotNull Optional<TableObj> read(Map<String, YamlFile> database, String tableName, TableObj key) {
        if(!(database instanceof Map)) throw new IllegalStateException();

        if(!StringUtils.endsWithIgnoreCase(tableName,".yml")) tableName = tableName + ".yml";

        YamlFile file = database.get(tableName);
        if(file == null || !file.exists()) return Optional.empty();
        String s = key.object.toString();
        Object res = file.get(s);
        if(res == null) return Optional.empty();
        return Optional.of(new TableObj(res));
    }

    @Override
    public void write(Map<String, YamlFile> database, String tableName, Map<TableObj,TableObj> keyValuePairs) {
        if(!StringUtils.endsWithIgnoreCase(tableName,".yml")) tableName = tableName + ".yml";
        YamlFile file = database.get(tableName);
        if(file == null) file = new YamlFile(directory.getAbsolutePath() + File.separator + tableName);
        if(!file.exists()) {
            if(database.containsKey("default.yml")) {
                try {
                    database.get("default.yml").copyTo(file.getFilePath());
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
            else {
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }
        try {
            file.loadWithComments();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        for(Map.Entry<TableObj,TableObj> entry : keyValuePairs.entrySet()) {
            String keyStr = entry.getKey().object.toString();
            Object o = file.get(keyStr);
            Object value = entry.getValue().object;

            if (o instanceof ConfigurationSection) {
                ConfigurationSection configurationSection = (ConfigurationSection) o;

                if (configurationSection.getName().equalsIgnoreCase("shape") && value instanceof String) {
                    String shapeName = (String) value;
                    value = RTP.factoryMap.get(RTP.factoryNames.shape).getOrDefault(shapeName);
                }

                if (configurationSection.getName().equalsIgnoreCase("vert") && value instanceof String) {
                    String vertName = (String) value;
                    value = RTP.factoryMap.get(RTP.factoryNames.vert).getOrDefault(vertName);
                }

                if (value instanceof FactoryValue<?>) {
                    EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
                    Map<String, Object> map = new HashMap<>();
                    for (Map.Entry<? extends Enum<?>, Object> d : data.entrySet())
                        map.put(d.getKey().name(), d.getValue());
                    setSection((ConfigurationSection) o, map);
                } else if (value instanceof Map) {
                    setSection((ConfigurationSection) o, (Map<String, Object>) value);
                } else {
                    throw new IllegalArgumentException("expected map or similar for " + keyStr);
                }
                file.set(keyStr, o);
            } else {
                file.set(keyStr, value);
            }
        }
    }

    @Override
    public void setValue(String tableName, Map<?,?> keyValuePairs) {
        super.setValue(tableName, keyValuePairs);
        Map<TableObj,TableObj> writeValues = new HashMap<>();
        keyValuePairs.forEach((o, o2) -> writeValues.put(new TableObj(o), new TableObj(o2)));
        Map.Entry<String,Map<TableObj,TableObj>> writeRequest
                = new AbstractMap.SimpleEntry<>(tableName,writeValues);
        writeQueue.add(writeRequest);
    }

    private static void setSection(ConfigurationSection section, Map<String,Object> map) {
        Map<String, Object> mapValues = section.getMapValues(false);

        for(Map.Entry<String,Object> e : mapValues.entrySet()) {
            Object o = e.getValue();
            if(!map.containsKey(e.getKey())) continue;
            Object value = map.get(e.getKey());
            if(o instanceof ConfigurationSection) {
                if(value instanceof FactoryValue<?>) {
                    EnumMap<?, Object> data = ((FactoryValue<?>) value).getData();
                    Map<String,Object> subMap = new HashMap<>();
                    for(Map.Entry<? extends Enum<?>,?> d : data.entrySet()) subMap.put(d.getKey().name(),d.getValue());
                    setSection((ConfigurationSection) o,subMap);
                }
                else if(value instanceof Map) {
                    setSection((ConfigurationSection) o, (Map<String, Object>) value);
                }
                else throw new IllegalArgumentException();
            }
            else {
                section.set(e.getKey(),value);
            }
        }
    }
}
