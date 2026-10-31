package org.wynnvets.retro.pack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

/** A resource pack over files held in memory, some of which can fail to read. */
public final class MemoryPack implements PackResources {

    private final String id;
    private final Map<Identifier, IoSupplier<InputStream>> files = new TreeMap<>();
    private boolean closed;
    private boolean throwsOnLookup;
    private boolean overClasspath;

    /**
     * An empty pack.
     *
     * @param id its pack id
     */
    public MemoryPack(String id) {
        this.id = id;
    }

    /**
     * Adds a file.
     *
     * @param file its full path, such as {@code minecraft:textures/block/stone.png}
     * @param bytes its bytes
     * @return this pack
     */
    public MemoryPack file(String file, byte[] bytes) {
        files.put(Identifier.parse(file), () -> new ByteArrayInputStream(bytes));
        return this;
    }

    /**
     * Adds a text file.
     *
     * @param file its full path
     * @param text its text
     * @return this pack
     */
    public MemoryPack file(String file, String text) {
        return file(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Adds a texture's PNG.
     *
     * @param textureId the texture id, such as {@code minecraft:block/stone}
     * @param png its bytes
     * @return this pack
     */
    public MemoryPack texture(String textureId, byte[] png) {
        files.put(PackFiles.textureFile(textureId), () -> new ByteArrayInputStream(png));
        return this;
    }

    /**
     * Adds a file that is there but throws {@code IOException("cannot read")} when read.
     *
     * @param file its full path
     * @return this pack
     */
    public MemoryPack unreadable(String file) {
        files.put(
                Identifier.parse(file),
                () -> {
                    throw new IOException("cannot read");
                });
        return this;
    }

    /**
     * Makes every lookup of a file throw {@code IllegalStateException}, as a closed pack may.
     *
     * @return this pack
     */
    public MemoryPack throwingOnLookup() {
        throwsOnLookup = true;
        return this;
    }

    /**
     * Makes a file this pack does not hold answer from the test classpath, which carries the client
     * jar's assets. Only a lookup does; a listing still sees what this pack holds.
     *
     * @return this pack
     */
    public MemoryPack overClasspath() {
        overClasspath = true;
        return this;
    }

    /** Whether {@link #close()} has been called. */
    public boolean closed() {
        return closed;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (throwsOnLookup) throw new IllegalStateException("cannot look");
        IoSupplier<InputStream> held = files.get(location);
        if (held != null || !overClasspath) return held;
        URL jar =
                MemoryPack.class.getResource(
                        "/assets/" + location.getNamespace() + "/" + location.getPath());
        return jar == null ? null : jar::openStream;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        String folder = path + "/";
        files.forEach(
                (file, contents) -> {
                    if (file.getNamespace().equals(namespace)
                            && file.getPath().startsWith(folder)) {
                        output.accept(file, contents);
                    }
                });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        Set<String> namespaces = new TreeSet<>();
        files.keySet().forEach(file -> namespaces.add(file.getNamespace()));
        return namespaces;
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return new PackLocationInfo(
                id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
    }

    @Override
    public void close() {
        closed = true;
    }
}
