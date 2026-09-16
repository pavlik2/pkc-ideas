package dev.customagent.plugin;

import java.util.Map;

/** Implement this SPI and register it in META-INF/services to add a processor node. */
public interface ProcessingPlugin {
    String id();
    String name();
    default String description() { return ""; }
    String process(String input, Map<String, String> configuration) throws Exception;
}
