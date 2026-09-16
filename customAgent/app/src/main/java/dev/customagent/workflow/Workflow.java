package dev.customagent.workflow;

import java.util.List;
import java.util.Map;

public record Workflow(List<Node> nodes, List<Edge> edges) {
    public Workflow { nodes = nodes == null ? List.of() : List.copyOf(nodes); edges = edges == null ? List.of() : List.copyOf(edges); }
    public record Node(String id, String type, double x, double y, Map<String, String> data) {
        public Node { data = data == null ? Map.of() : Map.copyOf(data); }
    }
    public record Edge(String id, String source, String target, String route) {}
}
