# Custom Agent

A visual, ComfyUI-inspired workflow builder for OpenAI-compatible language models. The backend is Spring Boot and Spring AI; the frontend is dependency-free HTML/CSS/JavaScript.

## Run

Requires Java 17+ and Maven 3.8+.

```bash
mvn clean package
mkdir -p app/plugins
cp sample-plugin/target/uppercase-plugin-*.jar app/plugins/
cd app
java -jar target/custom-agent-app-0.1.0-SNAPSHOT.jar
```

Open http://localhost:8080. Add nodes, drag them, connect an output dot to an input dot, configure the nodes in the inspector, then run or save the workflow. Set `OPENAI_API_KEY`, or enter a key on an AI node. Node keys are sent only to that node's configured compatible endpoint and are persisted when the workflow is saved, so prefer the environment variable for shared machines.

## Node behavior

- **Input** starts a graph with configured text.
- **AI Model** sends its incoming text through `{{input}}` in the prompt. URL and model are configured per node.
- **Processor** invokes a Java JAR plugin.
- **Router** chooses the first outgoing edge whose route label occurs in the incoming answer (case-insensitive); `default` is the fallback. Add route labels while connecting it.
- **Output** displays the final incoming value.

Graphs should be directed and acyclic. Multiple incoming values are joined with newlines.

## Create a Java processing plugin

Depend on `dev.customagent:plugin-api:0.1.0-SNAPSHOT` with `provided` scope, implement `ProcessingPlugin`, and add the implementation's fully qualified name to:

`src/main/resources/META-INF/services/dev.customagent.plugin.ProcessingPlugin`

Build the JAR, copy it to `app/plugins`, and click **Reload JAR plugins**. See `sample-plugin` for a complete example. Plugin JARs execute inside the server process and must be trusted.

## Configuration

| Environment variable | Default |
|---|---|
| `PORT` | `8080` |
| `CUSTOM_AGENT_PLUGINS_DIR` | `plugins` |
| `CUSTOM_AGENT_WORKFLOW_FILE` | `data/workflow.json` |
| `OPENAI_API_KEY` | empty |

REST endpoints are `GET/PUT /api/workflow`, `POST /api/workflow/run`, `GET /api/plugins`, and `POST /api/plugins/reload`.
