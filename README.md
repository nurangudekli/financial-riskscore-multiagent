# Java RAG KYC — Multi‑Agent + MCP (Azure‑first, single Spring Boot app)

This project demonstrates **AI orchestration with Java & Azure**:
- **Agents**
  - **Extractor** → Azure **Document Intelligence** (prebuilt-id) to parse ID fields.
  - **Screening** → **Azure AI Search** (supports semantic + vector hybrid).
  - **Fraud** → **Azure AI Search** (context) + **Azure OpenAI** (LangChain4j) for JSON triage.
  - **Risk** → **Azure OpenAI** (LangChain4j) for deterministic JSON risk scoring.
- **Fan‑out / Fan‑in orchestration**
  - Spring Orchestrator endpoint (`POST /api/kyc/start`) and **Durable Functions** orchestrator (Java) under `azure-functions-kyc/`.
- **RAG & Data**
  - Local **pgvector** (Docker Compose) and **loaders** for Azure AI Search & Vector Store.
- **MCP Tool Server**
  - Exposes agents & orchestrator as **Model Context Protocol** tools (Node/TS).

---

## Quick Start

### 1) Requirements
- JDK 17, Maven
- Docker (for pgvector)
- Node 18+ (for MCP bridge, optional)
- Azure resources (Azure OpenAI, AI Search, Document Intelligence) or use local-only pieces

### 2) Spin up vector DB
```bash
docker compose up -d
```

### 3) Environment
```bash
# Azure OpenAI (LangChain4j)
export AZURE_OPENAI_ENDPOINT=https://<aoai>.openai.azure.com
export AZURE_OPENAI_API_KEY=<key>
export AZURE_OPENAI_DEPLOYMENT=gpt-4o-mini
export AZURE_OPENAI_EMBEDDING=text-embedding-3-small

# Azure AI Search
export SEARCH_ENDPOINT=https://<search>.search.windows.net
export SEARCH_API_KEY=<key>
export SEARCH_INDEX=sanctions-demo

# Azure Document Intelligence
export AI_DOCINT_ENDPOINT=https://<docintel>.cognitiveservices.azure.com
# Either use a key OR Managed Identity (DefaultAzureCredential)
export AI_DOCINT_KEY=<cognitive-services-key>   # optional
```

### 4) Run the app
```bash
mvn spring-boot:run
```

### 5) (Optional) MCP bridge
```bash
cd tools/mcp-bridge
npm i && npm run build
API_BASE=http://localhost:8080 npm start
```

---

## Endpoints

### Ingestion
- **POST `/api/ingest`** — Ingest PDF by URL or path into the local vector store (collection metadata supported).

### Agents (Azure‑backed)
- **POST `/api/agents/extract`** — `{ "docUrl": "https://..." }` → Azure **Document Intelligence** `prebuilt-id` output as JSON.
- **POST `/api/agents/screen`** — `{ "name": "...", "birthDate": "YYYY-MM-DD" }` → **Azure AI Search** results (JSON).
- **POST `/api/agents/fraud`** — `{ "query": "..." }` → **Hybrid search** (AI Search semantic + vector) + **Azure OpenAI** JSON triage.
- **POST `/api/agents/risk`** — `{ "sanctionsContext": "...", "docSignals": "...", "fraudSignals": "..." }` → **Azure OpenAI** JSON risk score (validated).

### Orchestration
- **POST `/api/kyc/start`** — Fan‑out (Extractor, Screening, Fraud) → Fan‑in (**Risk**). Returns JSON.
- **POST `/api/kyc/inspect-id`** — `{ "docUrl": "..." }` → ID quality & validity (expired / MRZ / blur) assessment.

### Loaders
- **POST `/api/load/sanctions-to-search`** — Push **CSV** sanctions to **Azure AI Search** index.
- **POST `/api/load/sanctions-to-search-with-vectors`** — Same as above, plus **embeddings** for vector field.
- **POST `/api/load/sanctions-to-vector`** — Ingest sanctions CSV into **pgvector** (`collection=sanctions`).

### Search (hybrid example)
- **POST `/api/load/search/hybrid`** — Body `{ "q": "..." }`. Runs semantic + vector KNN over Azure AI Search (see loader code).

---

## ID Inspection (Onboarding)

**`POST /api/kyc/inspect-id`** → returns structured JSON:
- **quality**: average OCR confidence; blur/glare suspicion
- **validity**: expired, expiry date, DOB
- **consistency**: MRZ presence/validity & consistency with fields (ICAO 9303 checksums)
- **risk**: LOW / MEDIUM / HIGH with reasons
- **idInfo**: extracted ID attributes (document number, first/last name)

Implementation: `IdInspectionService` uses **Azure Document Intelligence** + MRZ checksum utilities and simple heuristics.

---

## Validation (JSON Schema)

Responses from **Fraud** and **Risk** agents are validated against JSON schemas:
- `src/main/resources/schemas/fraud.schema.json`
- `src/main/resources/schemas/risk.schema.json`

Utility: `JsonSchemaValidator` (based on *networknt* JSON schema validator).

---

## Azure Durable Functions (Java)

Folder `azure-functions-kyc/` contains a **Durable Functions** orchestrator:
- **Start**: `POST /api/kyc/start` (Function) schedules `KycOrchestrator`
- **Fan‑out**: `CallExtractor`, `CallScreening`, `CallFraud`
- **Fan‑in**: `CallRiskLc4j` → returns risk JSON

### Local run
```bash
cd azure-functions-kyc
mvn clean package
APP_BASE=http://localhost:8080 mvn azure-functions:run
```

### Deploy (Consumption)
Use the bash steps in your runbook (resource group, storage, function app creation, `azure-functions:deploy`) and set app settings:
- `APP_BASE` (public URL of the Spring Boot API)
- Azure OpenAI & Document Intelligence env vars

---

## Sample Data & Postman

- **Static PDFs** (served by Spring):  
  - `http://localhost:8080/docs/kyc_policy.pdf`  
  - `http://localhost:8080/docs/sanctions_guideline.pdf`  
  - `http://localhost:8080/docs/kyc_checklist.pdf`  
  - `http://localhost:8080/docs/fraud_signals.pdf`  
  - `http://localhost:8080/docs/risk_policy.pdf`

- **Sanctions CSV**: `src/main/resources/sample-data/sanctions.csv`

- **Postman Collection**: `postman/Java-RAG-KYC-MultiAgent.postman_collection.json`

---

## Configuration Reference (env)

| Purpose | Variable |
|---|---|
| AOAI chat | `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT` |
| AOAI embedding | `AZURE_OPENAI_EMBEDDING` |
| AI Search | `SEARCH_ENDPOINT`, `SEARCH_API_KEY`, `SEARCH_INDEX` |
| Document Intelligence | `AI_DOCINT_ENDPOINT`, `AI_DOCINT_KEY` *(or Managed Identity)* |
| Spring AI embedding fallback | `spring.ai.openai.embedding.options.model` (application.properties) |
| Functions (orchestrator) | `APP_BASE` (points to Spring API) |

---

## MCP Tools (optional)

`tools/mcp-bridge` exposes:
- `ingest`, `agent_extract`, `agent_screen`, `agent_fraud`, `agent_risk`, `kyc_orchestrate`

Run:
```bash
cd tools/mcp-bridge
npm i && npm run build
API_BASE=http://localhost:8080 npm start
```

---

## Notes
- This repository is **demo‑friendly**: light prompts, explicit JSON, and guardrails via JSON Schema.
- Replace sample PDFs with **real ID images** for best ID Inspection results.


### Using classpath resources (no upload, no public URL)
Place your test documents under `src/main/resources/customer-docs/` and call endpoints with `resourcePath`:

```bash
# Extractor (Azure Document Intelligence) from classpath
curl -s http://localhost:8080/api/agents/extract -H 'Content-Type: application/json'  -d '{"resourcePath":"customer-docs/sample-id.pdf"}' | jq .

# ID Inspection from classpath
curl -s http://localhost:8080/api/kyc/inspect-id -H 'Content-Type: application/json'  -d '{"resourcePath":"customer-docs/sample-id.pdf"}' | jq .
```
> Note: In production, prefer upload or signed URL; this path is **classpath-only** for local demos.
