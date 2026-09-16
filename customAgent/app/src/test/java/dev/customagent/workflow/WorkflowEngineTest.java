package dev.customagent.workflow;

import dev.customagent.plugin.ProcessingPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowEngineTest {
    private final PluginManager manager = mock(PluginManager.class);
    private final WorkflowEngine engine = new WorkflowEngine(manager);

    @Test void executesProcessorPlugin() throws Exception {
        ProcessingPlugin plugin = mock(ProcessingPlugin.class);
        when(manager.get("uppercase")).thenReturn(plugin);
        when(plugin.process(eq("hello"), anyMap())).thenReturn("HELLO");
        Workflow flow = new Workflow(List.of(
                node("in", "input", Map.of("input", "hello")),
                node("process", "processor", Map.of("pluginId", "uppercase")),
                node("out", "output", Map.of())), List.of(edge("in", "process", ""), edge("process", "out", "")));

        assertThat(engine.execute(flow, "").outputs().get("out")).isEqualTo("HELLO");
    }

    @Test void routerSelectsMatchingBranch() throws Exception {
        Workflow flow = new Workflow(List.of(
                node("in", "input", Map.of("input", "answer is YES")), node("route", "router", Map.of()),
                node("yes", "output", Map.of()), node("no", "output", Map.of())), List.of(
                edge("in", "route", ""), edge("route", "yes", "yes"), edge("route", "no", "default")));

        Map<String,String> outputs = engine.execute(flow, "").outputs();
        assertThat(outputs).containsEntry("yes", "answer is YES").doesNotContainKey("no");
    }

    private Workflow.Node node(String id,String type,Map<String,String> data){return new Workflow.Node(id,type,0,0,data);}
    private Workflow.Edge edge(String source,String target,String route){return new Workflow.Edge(source+target,source,target,route);}
}
