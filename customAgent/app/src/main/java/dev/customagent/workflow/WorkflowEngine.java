package dev.customagent.workflow;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowEngine {
    private final PluginManager plugins;
    public WorkflowEngine(PluginManager plugins) { this.plugins = plugins; }

    public Result execute(Workflow workflow, String initialInput) throws Exception {
        Map<String, Workflow.Node> nodes = workflow.nodes().stream().collect(Collectors.toMap(Workflow.Node::id, Function.identity()));
        Map<String, List<Workflow.Edge>> outgoing = workflow.edges().stream().collect(Collectors.groupingBy(Workflow.Edge::source));
        Map<String, Integer> incoming = new HashMap<>(); workflow.nodes().forEach(n -> incoming.put(n.id(), 0));
        workflow.edges().forEach(e -> incoming.computeIfPresent(e.target(), (k, v) -> v + 1));
        Deque<String> queue = new ArrayDeque<>(); incoming.forEach((id, count) -> { if (count == 0) queue.add(id); });
        Map<String, String> output = new LinkedHashMap<>(); Set<String> executed = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.remove(); if (!executed.add(id)) continue;
            Workflow.Node node = require(nodes, id); String input = joinedInput(workflow, output, id, initialInput);
            String value = run(node, input); output.put(id, value);
            List<Workflow.Edge> next = outgoing.getOrDefault(id, List.of());
            if ("router".equals(node.type())) next = route(next, value);
            for (Workflow.Edge edge : next) if (nodes.containsKey(edge.target())) queue.add(edge.target());
        }
        if (executed.isEmpty() && !workflow.nodes().isEmpty()) throw new IllegalArgumentException("Workflow has a cycle or no start node");
        return new Result(output);
    }

    private String run(Workflow.Node node, String input) throws Exception {
        return switch (node.type()) {
            case "input" -> node.data().getOrDefault("input", input);
            case "ai" -> ai(node, input);
            case "processor" -> plugins.get(node.data().getOrDefault("pluginId", "")).process(input, node.data());
            case "router" -> input;
            case "output" -> input;
            default -> throw new IllegalArgumentException("Unsupported node type: " + node.type());
        };
    }
    private String ai(Workflow.Node node, String input) {
        String baseUrl = node.data().getOrDefault("baseUrl", "https://api.openai.com");
        String key = node.data().getOrDefault("apiKey", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        String model = node.data().getOrDefault("model", "gpt-4o-mini");
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(key).build();
        OpenAiChatModel chat = OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder().model(model).build()).build();
        String template = node.data().getOrDefault("prompt", "{{input}}");
        String user = template.replace("{{input}}", input);
        var messages = new ArrayList<org.springframework.ai.chat.messages.Message>();
        String system = node.data().getOrDefault("system", ""); if (!system.isBlank()) messages.add(new SystemMessage(system));
        messages.add(new UserMessage(user)); return Objects.requireNonNull(chat.call(new Prompt(messages)).getResult().getOutput().getText());
    }
    private List<Workflow.Edge> route(List<Workflow.Edge> edges, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return edges.stream().filter(e -> e.route() == null || e.route().isBlank() || "default".equalsIgnoreCase(e.route()) || lower.contains(e.route().toLowerCase(Locale.ROOT))).findFirst().map(List::of).orElse(List.of());
    }
    private String joinedInput(Workflow w, Map<String,String> output, String id, String fallback) {
        String joined = w.edges().stream().filter(e -> e.target().equals(id)).map(Workflow.Edge::source).map(output::get).filter(Objects::nonNull).collect(Collectors.joining("\n")); return joined.isBlank() ? fallback : joined;
    }
    private Workflow.Node require(Map<String, Workflow.Node> nodes, String id) { return Optional.ofNullable(nodes.get(id)).orElseThrow(() -> new IllegalArgumentException("Missing node: " + id)); }
    public record Result(Map<String, String> outputs) {}
}
