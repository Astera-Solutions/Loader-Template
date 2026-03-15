package dev.astera.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.lib.classtweaker.impl.ClassTweakerImpl;
import net.fabricmc.loader.impl.lib.classtweaker.reader.ClassTweakerReaderImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Runtime { /* runtime shit */
    private static final Logger LOGGER = LoggerFactory.getLogger("loader");
    private static final Path NESTED_JAR_CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "loader-nested-jars");

    private Runtime() {
    }

    public static void registerModFromJar(Path jarPath) throws Exception {
        addToClassPath(jarPath);
        loadNestedJars(jarPath);

        LoaderModMetadata metadata = loadMetadataFromJar(jarPath);
        if (metadata == null) {
            throw new IllegalStateException("No Fabric metadata could be parsed from " + jarPath);
        }

        if (FabricLoaderImpl.INSTANCE.isModLoaded(metadata.getId())) {
            LOGGER.info("Skipping remote mod {} because it is already loaded", metadata.getId());
            return;
        }

        Object candidate = createModCandidate(metadata, jarPath);
        Object container = candidateToContainer(candidate);
        addModContainer(container);
    }

    public static void injectAccessWidener(String resourcePath) throws Exception {
        ClassTweakerImpl classTweaker = (ClassTweakerImpl) FabricLoaderImpl.INSTANCE.getClassTweaker();
        ClassTweakerReaderImpl reader = new ClassTweakerReaderImpl(classTweaker);
        String runtimeNamespace = FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace();

        try (InputStream inputStream = Runtime.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Access widener resource not found: " + resourcePath);
            }

            LOGGER.info("Injecting access widener {} with runtime namespace {}", resourcePath, runtimeNamespace);
            reader.read(inputStream.readAllBytes(), runtimeNamespace);
        }
    }

    public static String getAccessWidenerFileName(Path jarPath) throws IOException {
        JsonObject json = readFabricModJson(jarPath);
        return json != null && json.has("accessWidener") ? json.get("accessWidener").getAsString() : null;
    }

    public static List<String> getMixinFileNames(Path jarPath) throws IOException {
        JsonObject json = readFabricModJson(jarPath);
        if (json == null || !json.has("mixins")) {
            return List.of();
        }

        List<String> mixinConfigs = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("mixins")) {
            if (element.isJsonPrimitive()) {
                mixinConfigs.add(element.getAsString());
            }
        }
        return mixinConfigs;
    }

    public static void invokeEntrypoints(Path jarPath) throws Exception {
        JsonObject json = readFabricModJson(jarPath);
        if (json == null || !json.has("entrypoints")) {
            return;
        }

        JsonObject entrypoints = json.getAsJsonObject("entrypoints");
        Map<String, Object> instances = new HashMap<>();

        invokeEntrypoints(entrypoints, "preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch, instances);

        if (shouldDeferClientEntrypoints(json)) {
            scheduleClientEntrypoints(entrypoints, instances);
            return;
        }

        invokeEntrypoints(entrypoints, "main", ModInitializer.class, ModInitializer::onInitialize, instances);
        invokeEntrypoints(entrypoints, "server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer, instances);
    }

    private static boolean shouldDeferClientEntrypoints(JsonObject modJson) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return false;
        }

        String environment = modJson.has("environment") ? modJson.get("environment").getAsString() : "*";
        return "*".equals(environment) || "client".equalsIgnoreCase(environment);
    }

    private static void addToClassPath(Path jarPath) {
        FabricLauncherBase.getLauncher().addToClassPath(jarPath);
    }

    private static void loadNestedJars(Path jarPath) throws IOException {
        clearDirectory(NESTED_JAR_CACHE_DIR);
        Files.createDirectories(NESTED_JAR_CACHE_DIR);

        try (var fileSystem = FileSystems.newFileSystem(jarPath);
             var walk = Files.walk(fileSystem.getPath("META-INF", "jars"))) {
            for (Path nestedJar : walk.filter(Files::isRegularFile).filter(Runtime::isJarFile).toList()) {
                Path extractedJar = NESTED_JAR_CACHE_DIR.resolve(nestedJar.getFileName().toString());
                Files.copy(nestedJar, extractedJar, StandardCopyOption.REPLACE_EXISTING);
                addToClassPath(extractedJar);
            }
        } catch (NoSuchFileException ignored) {
            // Jar has no nested dependencies.
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isJarFile(Path path) {
        return path.toString().endsWith(".jar");
    }

    private static LoaderModMetadata loadMetadataFromJar(Path jarPath) throws Exception {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }

            Method parseMetadataMethod = Class.forName("net.fabricmc.loader.impl.metadata.ModMetadataParser")
                    .getDeclaredMethod(
                            "parseMetadata",
                            InputStream.class,
                            String.class,
                            List.class,
                            VersionOverrides.class,
                            DependencyOverrides.class,
                            boolean.class
                    );
            parseMetadataMethod.setAccessible(true);

            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                return (LoaderModMetadata) parseMetadataMethod.invoke(
                        null,
                        inputStream,
                        jarPath.getFileName().toString(),
                        List.of(),
                        new VersionOverrides(),
                        new DependencyOverrides(Path.of("")),
                        false
                );
            }
        }
    }

    private static Object createModCandidate(LoaderModMetadata metadata, Path jarPath) throws Exception {
        Class<?> modCandidateClass = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
        Method createPlainMethod = modCandidateClass.getDeclaredMethod(
                "createPlain",
                List.class,
                LoaderModMetadata.class,
                boolean.class,
                Collection.class
        );
        createPlainMethod.setAccessible(true);
        return createPlainMethod.invoke(null, List.of(jarPath), metadata, false, List.of());
    }

    private static Object candidateToContainer(Object candidate) throws Exception {
        Class<?> modCandidateClass = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
        Constructor<?> constructor = Class.forName("net.fabricmc.loader.impl.ModContainerImpl")
                .getDeclaredConstructor(modCandidateClass);
        constructor.setAccessible(true);
        return constructor.newInstance(candidate);
    }

    @SuppressWarnings("unchecked")
    private static void addModContainer(Object container) throws Exception {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        Field modsField = loader.getClass().getDeclaredField("mods");
        modsField.setAccessible(true);
        ((List<Object>) modsField.get(loader)).add(container);

        Method getMetadataMethod = container.getClass().getDeclaredMethod("getMetadata");
        getMetadataMethod.setAccessible(true);
        LoaderModMetadata metadata = (LoaderModMetadata) getMetadataMethod.invoke(container);

        Field modMapField = loader.getClass().getDeclaredField("modMap");
        modMapField.setAccessible(true);
        ((Map<String, Object>) modMapField.get(loader)).put(metadata.getId(), container);
    }

    private static JsonObject readFabricModJson(Path jarPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zipFile.getEntry("fabric.mod.json");
            if (entry == null) {
                return null;
            }

            try (InputStream inputStream = zipFile.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(inputStream)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static void scheduleClientEntrypoints(JsonObject entrypoints, Map<String, Object> instances) {
        List<String> mainEntrypoints = getEntrypointClassNames(entrypoints, "main");
        List<String> clientEntrypoints = getEntrypointClassNames(entrypoints, "client");
        if (mainEntrypoints.isEmpty() && clientEntrypoints.isEmpty()) {
            return;
        }

        AtomicBoolean invoked = new AtomicBoolean(false);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (!invoked.compareAndSet(false, true)) {
                return;
            }

            invokeDeferredEntrypoints(mainEntrypoints, ModInitializer.class, ModInitializer::onInitialize, instances, "main");
            invokeDeferredEntrypoints(clientEntrypoints, ClientModInitializer.class, ClientModInitializer::onInitializeClient, instances, "client");
        });
    }

    private static <T> void invokeDeferredEntrypoints(
            List<String> entrypointClasses,
            Class<T> expectedType,
            ThrowingConsumer<T> invoker,
            Map<String, Object> instances,
            String phase
    ) {
        for (String className : entrypointClasses) {
            Object instance = instances.computeIfAbsent(className, Runtime::newEntrypointInstance);
            if (!expectedType.isInstance(instance)) {
                throw new IllegalStateException("Entrypoint " + className + " does not implement " + expectedType.getName());
            }

            LOGGER.info("Invoking deferred {} entrypoint {}", phase, className);
            try {
                invoker.accept(expectedType.cast(instance));
            } catch (Exception exception) {
                throw new RuntimeException("Failed to invoke deferred " + phase + " entrypoint " + className, exception);
            }
        }
    }

    private static <T> void invokeEntrypoints(
            JsonObject entrypoints,
            String key,
            Class<T> expectedType,
            ThrowingConsumer<T> invoker,
            Map<String, Object> instances
    ) throws Exception {
        for (String className : getEntrypointClassNames(entrypoints, key)) {
            Object instance = instances.computeIfAbsent(className, Runtime::newEntrypointInstance);
            if (!expectedType.isInstance(instance)) {
                throw new IllegalStateException("Entrypoint " + className + " does not implement " + expectedType.getName());
            }

            LOGGER.info("Invoking {} entrypoint {}", key, className);
            invoker.accept(expectedType.cast(instance));
        }
    }

    private static List<String> getEntrypointClassNames(JsonObject entrypoints, String key) {
        if (!entrypoints.has(key)) {
            return List.of();
        }

        JsonArray entrypointArray = entrypoints.getAsJsonArray(key);
        List<String> classNames = new ArrayList<>(entrypointArray.size());
        for (JsonElement element : entrypointArray) {
            if (element.isJsonPrimitive()) {
                classNames.add(element.getAsString());
            } else if (element.isJsonObject() && element.getAsJsonObject().has("value")) {
                classNames.add(element.getAsJsonObject().get("value").getAsString());
            }
        }
        return classNames;
    }

    private static Object newEntrypointInstance(String className) {
        try {
            Class<?> entrypointClass = Class.forName(className, true, Runtime.class.getClassLoader());
            Constructor<?> constructor = entrypointClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to instantiate entrypoint " + className, exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
