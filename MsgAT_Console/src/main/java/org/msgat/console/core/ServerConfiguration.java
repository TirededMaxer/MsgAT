package org.msgat.console.core;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.msgat.console.Main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Configuration is resolved relative to the executable JAR, never the shell's working directory. */
public record ServerConfiguration(int port, Path file) {
    public static ServerConfiguration load() throws Exception {
        Path executable = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path directory = Files.isDirectory(executable) ? executable : executable.getParent();
        Path file = directory.resolve("server-config.json");
        try {
            Files.writeString(file, "{\n  \"port\": 34478\n}\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Keep the operator's existing settings.
        }
        if (Files.size(file) > 16 * 1024) throw new IOException("Configuration is too large: " + file);
        int port = 34478;
        boolean foundPort = false;
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!"port".equals(key)) throw new IOException("Unknown configuration field: " + key);
                if (foundPort || reader.peek() != JsonToken.NUMBER) throw new IOException("port must be one integer from 1 to 65535");
                String value = reader.nextString();
                if (!value.matches("[0-9]{1,5}")) throw new IOException("port must be one integer from 1 to 65535");
                port = Integer.parseInt(value);
                if (port < 1 || port > 65535) throw new IOException("port must be between 1 and 65535");
                foundPort = true;
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected content after configuration object");
        } catch (IOException | IllegalStateException error) {
            throw new IOException("Invalid server configuration " + file + ": " + error.getMessage(), error);
        }
        return new ServerConfiguration(port, file);
    }
}
