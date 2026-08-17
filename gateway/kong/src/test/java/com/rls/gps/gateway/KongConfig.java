package com.rls.gps.gateway;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Reads {@code kong.yml} so the tests can assert on the gateway's routing surface. */
final class KongConfig {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Map<String, Object> root;

    private KongConfig(Map<String, Object> root) {
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    static KongConfig load() {
        File file = new File("kong.yml");
        if (!file.isFile()) {
            throw new IllegalStateException("kong.yml not found at " + file.getAbsolutePath());
        }
        try {
            return new KongConfig(YAML.readValue(file, Map.class));
        } catch (IOException ex) {
            throw new UncheckedIOException("kong.yml is not valid YAML", ex);
        }
    }

    String formatVersion() {
        return (String) root.get("_format_version");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> services() {
        return (List<Map<String, Object>>) root.getOrDefault("services", List.of());
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> globalPlugins() {
        return (List<Map<String, Object>>) root.getOrDefault("plugins", List.of());
    }

    /** Every route across every service. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> routes() {
        return services().stream()
                .flatMap(service -> ((List<Map<String, Object>>) service.getOrDefault("routes", List.of())).stream())
                .toList();
    }

    /** Every path Kong will accept, across every route. */
    @SuppressWarnings("unchecked")
    List<String> allPaths() {
        return routes().stream()
                .flatMap(route -> ((List<String>) route.getOrDefault("paths", List.of())).stream())
                .toList();
    }

    Map<String, Object> route(String name) {
        return routes().stream()
                .filter(route -> name.equals(route.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no route named '" + name + "' in kong.yml"));
    }

    @SuppressWarnings("unchecked")
    static List<String> pluginNames(Map<String, Object> owner) {
        return ((List<Map<String, Object>>) owner.getOrDefault("plugins", List.of())).stream()
                .map(plugin -> (String) plugin.get("name"))
                .toList();
    }

    /** A named plugin attached to a route or service. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> plugin(Map<String, Object> owner, String name) {
        return ((List<Map<String, Object>>) owner.getOrDefault("plugins", List.of())).stream()
                .filter(plugin -> name.equals(plugin.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no plugin '" + name + "' on " + owner.get("name")));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> globalPlugin(String name) {
        return globalPlugins().stream()
                .filter(plugin -> name.equals(plugin.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no global plugin '" + name + "' in kong.yml"));
    }
}
