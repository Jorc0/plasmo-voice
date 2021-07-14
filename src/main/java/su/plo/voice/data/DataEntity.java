package su.plo.voice.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import lombok.AllArgsConstructor;

import java.io.*;
import java.util.HashSet;
import java.util.UUID;

@AllArgsConstructor
public class DataEntity {
    private final static Gson gson = new Gson();

    public final HashSet<UUID> mutedClients;
    public final HashSet<UUID> whitelisted;

    public static DataEntity read() {
        File dataFile = new File("config/PlasmoVoice/data.json");
        if(dataFile.exists()) {
            try {
                JsonReader reader = new JsonReader(new FileReader(dataFile));
                try {
                    return gson.fromJson(reader, DataEntity.class);
                } catch (JsonSyntaxException j) {
                    dataFile.delete();
                }
            } catch (FileNotFoundException ignored) {}
        }

        return new DataEntity(new HashSet<>(), new HashSet<>());
    }

    public void save() {
        File configDir = new File("config/PlasmoVoice");
        configDir.mkdirs();

        try {
            try(Writer w = new FileWriter("config/PlasmoVoice/data.json")) {
                w.write(gson.toJson(this));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
