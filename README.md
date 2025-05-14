# Scrum Agent Demo

A lightweight Java demo illustrating agentic workflows (Scrum Master tasks) using Jlama + LangChain4j,
with file-based event and task queues. No Spring Boot or MongoDB.

## Prerequisites

1. Java 21+
2. Maven 3.6+
3. Download and place the 4-bit quantized model:
   ```
   mkdir -p models/tjake/Llama-3.2-1B-Instruct-JQ4
   # Download model.safetensors into that folder
   ```
4. (Optional) Adjust `modelPath` in `Application.java` if needed.

## Build

```bash
mvn package
```

## Run

```bash
# Run all agents
java -jar target/scrum-agent-demo-1.0-SNAPSHOT.jar -r

# Inject events from a file (e.g., to simulate manual events)
java -jar target/scrum-agent-demo-1.0-SNAPSHOT.jar -i path/to/event.json
```

Events and tasks are stored under `data/` and logs are printed to console.

