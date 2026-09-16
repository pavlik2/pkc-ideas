package dev.customagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.customagent.plugin.ProcessingPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.List;

@RestController @RequestMapping("/api")
public class WorkflowController {
    private final WorkflowEngine engine; private final PluginManager plugins; private final ObjectMapper mapper; private final Path workflowFile;
    public WorkflowController(WorkflowEngine engine, PluginManager plugins, ObjectMapper mapper, @Value("${custom-agent.workflow-file:data/workflow.json}") String path) { this.engine=engine; this.plugins=plugins; this.mapper=mapper; this.workflowFile=Path.of(path); }
    @GetMapping("/workflow") public Workflow load() throws Exception { return Files.exists(workflowFile) ? mapper.readValue(workflowFile.toFile(), Workflow.class) : new Workflow(List.of(), List.of()); }
    @PutMapping("/workflow") public Workflow save(@RequestBody Workflow workflow) throws Exception { if (workflowFile.getParent()!=null) Files.createDirectories(workflowFile.getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(workflowFile.toFile(), workflow); return workflow; }
    @PostMapping("/workflow/run") public WorkflowEngine.Result run(@RequestBody RunRequest request) throws Exception { return engine.execute(request.workflow(), request.input()==null?"":request.input()); }
    @GetMapping("/plugins") public List<PluginInfo> listPlugins() { return plugins.all().stream().map(p -> new PluginInfo(p.id(),p.name(),p.description())).toList(); }
    @PostMapping("/plugins/reload") public ResponseEntity<List<PluginInfo>> reload() throws Exception { plugins.reload(); return ResponseEntity.ok(listPlugins()); }
    public record RunRequest(Workflow workflow, String input) {} public record PluginInfo(String id,String name,String description) {}
    @ExceptionHandler(Exception.class) public ResponseEntity<ErrorResponse> error(Exception e) { return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage())); }
    public record ErrorResponse(String error) {}
}
