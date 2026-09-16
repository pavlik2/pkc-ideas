package dev.customagent.workflow;

import dev.customagent.plugin.ProcessingPlugin;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PluginManager implements AutoCloseable {
    private final Path directory;
    private final Map<String, ProcessingPlugin> plugins = new ConcurrentHashMap<>();
    private URLClassLoader loader;

    public PluginManager(@Value("${custom-agent.plugins-dir:plugins}") String directory) { this.directory = Path.of(directory); }

    @PostConstruct public synchronized void reload() throws IOException {
        closeLoader(); plugins.clear(); Files.createDirectories(directory);
        try (var jars = Files.list(directory)) {
            URL[] urls = jars.filter(p -> p.toString().endsWith(".jar")).map(this::url).toArray(URL[]::new);
            loader = new URLClassLoader(urls, ProcessingPlugin.class.getClassLoader());
            ServiceLoader.load(ProcessingPlugin.class, loader).forEach(p -> plugins.put(p.id(), p));
        }
    }
    public Collection<ProcessingPlugin> all() { return List.copyOf(plugins.values()); }
    public ProcessingPlugin get(String id) { return Optional.ofNullable(plugins.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown plugin: " + id)); }
    private URL url(Path p) { try { return p.toUri().toURL(); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    private void closeLoader() throws IOException { if (loader != null) loader.close(); }
    @Override public void close() throws IOException { closeLoader(); }
}
