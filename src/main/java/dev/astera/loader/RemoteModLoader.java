package dev.astera.loader;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

public final class RemoteModLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("loader");
    /* Replace this with ur mod url */
    private static final String MOD_URL = "https://github.com/0x12F/oyvey-ported/releases/download/1.21.11/oyvey--1.0.0.jar";
    private static final String MOD_FILE_NAME = "oyvey--1.0.0.jar"; /* Also if u want u can change the name*/
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private RemoteModLoader() {
    }

    public static void loadConfiguredMod() throws Exception {
        Path modJar = resolveCachedJar();
        LOGGER.info("Preparing remote Fabric mod from {}", modJar);

        Runtime.registerModFromJar(modJar);

        String accessWidener = Runtime.getAccessWidenerFileName(modJar);
        if (accessWidener != null && !accessWidener.isBlank()) {
            Runtime.injectAccessWidener(accessWidener);
        }

        List<String> mixinConfigs = Runtime.getMixinFileNames(modJar);
        for (String mixinConfig : mixinConfigs) {
            Mixins.addConfiguration(mixinConfig);
        }

        Runtime.invokeEntrypoints(modJar);

        LOGGER.info("Remote mod {} loaded successfully", modJar.getFileName());
    }

    private static Path resolveCachedJar() throws IOException, InterruptedException {
        Path cacheDir = FabricLoader.getInstance().getGameDir().resolve("loader").resolve("cache");
        Files.createDirectories(cacheDir);

        Path cachedJar = cacheDir.resolve(MOD_FILE_NAME);
        if (Files.isRegularFile(cachedJar) && Files.size(cachedJar) > 0L) {
            return cachedJar;
        }

        return downloadJar(cachedJar);
    }

    private static Path downloadJar(Path targetPath) throws IOException, InterruptedException {
        LOGGER.info("Downloading remote mod from {}", MOD_URL);

        HttpRequest request = HttpRequest.newBuilder(URI.create(MOD_URL))
                .GET()
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "loader/1.0.0")
                .build();

        Path tempFile = Files.createTempFile(targetPath.getParent(), "remote-mod-", ".jar");
        try {
            HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Unexpected response " + response.statusCode() + " while downloading " + MOD_URL);
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return targetPath;
        } catch (IOException | InterruptedException exception) {
            Files.deleteIfExists(tempFile);
            throw exception;
        }
    }
}
