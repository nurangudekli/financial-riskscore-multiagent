# Java RAG KYC — Multi‑Agent Orchestrator (Spring Boot + Azure)

**One‑line:** A single HTTP call that **extracts identity from an ID document**, **screens against sanctions**, **triages transactions for fraud patterns**, and returns a **unified risk score** with reasons.

---
 Demo video link  : https://screenrec.com/share/gMnsC1yTSV

---
## Table of Contents

* [Overview](#overview)
* [Architecture](#architecture)
* [What Each Agent Does](#what-each-agent-does)
* [Clone the Project](#create-the-project-spring-initializr)
* [Azure Resources](#azure-resources)
* [Configuration](#configuration)
* [Build & Run](#build--run)
* [Main Endpoint (One‑Call Orchestration)](#main-endpoint-onecall-orchestration)
* [Individual Agent Endpoints](#individual-agent-endpoints)
* [Sanctions Index Seeding (Optional)](#sanctions-index-seeding-optional)
* [Demo Script (Screen Recording Friendly)](#demo-script-screen-recording-friendly)
* [Troubleshooting](#troubleshooting)
* [Optional: Vector DB with pgvector](#optional-vector-db-with-pgvector)
* [Not Used in Core Flow](#not-used-in-core-flow)
* [Security Notes](#security-notes)
* [License](#license)

---

## Overview

This project is a **multi‑agent KYC/KYB orchestrator** built with **Spring Boot**. It fans out three AI‑backed checks **in parallel** and then fans in the results into a single decision:

1. **ID Extraction** — Uses **Azure AI Document Intelligence** (prebuilt model `prebuilt-idDocument`) to parse **name / DOB / document number / MRZ** and validates MRZ check digits + basic consistency.
2. **Sanctions Screening** — Queries an **Azure AI Search** index (e.g., `sanctions-demo`) to find matches for the extracted **name** (and DOB if available).
3. **Fraud Triage** — Uses **Azure OpenAI** to analyze **recent transactions** (or a free‑form narrative) for **velocity / structuring / threshold‑skirting** patterns.

Finally, an LLM‑driven **RiskAgent** fuses these signals and returns JSON:

```json
{
  "riskScore": 0,
  "level": "LOW",
  "reasons": ["No sanctions found", "Low suspicion of fraud"],
  "recommendation": "Continue monitoring with periodic reviews."
}
```

---

## Architecture

```
+-------------------+       +----------------------+       +--------------------+
|  Client (Postman) | ----> |  Orchestrator (API)  | ----> |  RiskAgent (LLM)   |
+-------------------+       |  /api/kyc/start      |       +--------------------+
                            |   ├─ ExtractorAgent  |---> Azure Document Intelligence
                            |   ├─ ScreeningAgent  |---> Azure AI Search
                            |   └─ FraudAgent      |---> Azure OpenAI (LLM)
                            +----------------------+
```

* **OrchestratorController** (`/api/kyc/start`) kicks off all agents **concurrently** via `CompletableFuture`.
* **ExtractorAgent** → `docSignals` (identity + MRZ/consistency flags).
* **ScreeningAgent** → `sanctionsContext` (AI Search hits with scores).
* **FraudAgent** → `fraudSignals` (LLM JSON summary of suspicious patterns).
* **RiskAgent** → final unified decision + optional component **breakdown**.

> There is also an optional **IngestController** for RAG (PDF → VectorStore) which is **not used** in the core `/api/kyc/start` flow.

---

## What Each Agent Does

### ExtractorAgent → `docSignals`

* Calls **Azure Document Intelligence** `prebuilt-idDocument`.
* Extracts name with robust fallbacks: `FullName` → `Name` → `FirstName+LastName` → `GivenName(s)+Surname` → **MRZ** fallback.
* Parses MRZ (TD3) and validates check digits (ICAO 9303).
* Computes `identityMismatch`, `expired`, `quality`, and human‑readable `reasons`.

### ScreeningAgent → `sanctionsContext`

* Queries **Azure AI Search** index (e.g., `sanctions-demo`) for likely matches.
* Returns `{ count, matches: [{score, doc}], reasons[] }`.
* **Requires** at least one **`searchable` string** field (e.g., `name`, `aliases`).

### FraudAgent → `fraudSignals`

* **Default**: Uses **Azure OpenAI** to assess **provided transactions** or a **free‑form question**.
* Returns strict JSON: `{ "suspicionLevel":"LOW|MEDIUM|HIGH", "reasons":[], "references":[] }`.
* Can be extended to hybrid (Search/pgvector context + LLM).

### RiskAgent → final decision

* Fuses `sanctionsContext`, `docSignals`, and `fraudSignals` into one compact JSON.
* Low‑temperature prompt for repeatability; optional JSON schema checks.

---

## Clone the project

 Choose:

    * **Project:** Maven
    * **Language:** Java
    * **Spring Boot:** 3.3.x
    * **Group:** `com.demo`
    * **Java:** 21 (or 17)
---

## Azure Resources

Create these services in the same Azure subscription/region where possible:

1. **Azure OpenAI**

    * Deploy a **chat** model: `gpt-4o-mini` (or similar)
    * (Optional) Deploy an **embedding** model: `text-embedding-3-small`
2. **Azure AI Search**

    * Note the **service URL** and **Admin key**
    * Create an index (e.g., `sanctions-demo`) and ingest sanctions CSV
3. **Azure AI Document Intelligence** (formerly Form Recognizer)

    * Note **endpoint** and **key**
    * Use prebuilt model **`prebuilt-idDocument`** (no training required)

---

## Configuration

Set these **environment variables** before running the app.

**Windows PowerShell**

```powershell
# Azure OpenAI
$env:AZURE_OPENAI_ENDPOINT    = "https://<aoai>.openai.azure.com/"
$env:AZURE_OPENAI_API_KEY     = "<AOAI_KEY>"
$env:AZURE_OPENAI_DEPLOYMENT  = "gpt-4o-mini"
$env:AZURE_OPENAI_EMBEDDING   = "text-embedding-3-small"  # optional

# Azure AI Search
$env:SEARCH_ENDPOINT          = "https://<search>.search.windows.net"
$env:SEARCH_API_KEY           = "<SEARCH_ADMIN_KEY>"
$env:SEARCH_INDEX             = "sanctions-demo"

# Azure Document Intelligence
$env:AI_DOCINT_ENDPOINT       = "https://<di>.cognitiveservices.azure.com"
$env:AI_DOCINT_KEY            = "<DI_KEY>"
```

**bash/zsh**

```bash
export AZURE_OPENAI_ENDPOINT="https://<aoai>.openai.azure.com/"
export AZURE_OPENAI_API_KEY="<AOAI_KEY>"
export AZURE_OPENAI_DEPLOYMENT="gpt-4o-mini"
export AZURE_OPENAI_EMBEDDING="text-embedding-3-small"   # optional

export SEARCH_ENDPOINT="https://<search>.search.windows.net"
export SEARCH_API_KEY="<SEARCH_ADMIN_KEY>"
export SEARCH_INDEX="sanctions-demo"

export AI_DOCINT_ENDPOINT="https://<di>.cognitiveservices.azure.com"
export AI_DOCINT_KEY="<DI_KEY>"
```

---

## Build & Run

```bash
mvn -q spring-boot:run
```

---

## Main Endpoint (One‑Call Orchestration)

**POST** `/api/kyc/start`

### Request body (record `KycStartRequest`)

```json
{
  "name": "",                 
  "birthDate": "",
  "question": "Highlight risky patterns (velocity, structuring, threshold-skirting) in the last 6 months.",
  "documentText": "customer-docs/passport_valid.png",   
  "transactions": [
    { "ts":"2025-02-10T09:12:00Z","amt":120,"country":"US","channel":"card_purchase","device":"M1" },
    { "ts":"2025-02-12T18:40:00Z","amt":75,"country":"US","channel":"card_purchase","device":"M1" }
  ]
}
```

* `documentText`: If it starts with `http`, the extractor fetches by URL; else it reads from classpath (`src/main/resources/...`).
* If `name` / `birthDate` are not provided, the orchestrator uses values parsed from the document (if extraction succeeded).

### Response (shape)

```json
{
  "inputs": {
    "sanctionsContext": { "count": 0, "matches": [], "reasons": [] },
    "docSignals": {
      "idInfo": { "fullName": "JANE DOE", "dob": "1992-04-12", "docNo": "...", "country": "EX" },
      "mrzValid": true,
      "identityMismatch": false,
      "expired": false,
      "quality": 0.92,
      "croppingHint": false,
      "reasons": []
    },
    "fraudSignals": { "raw": "{...}" },
    "identityUsed": { "name": "JANE DOE", "dob": "1992-04-12", "source": "document" },
    "documentRef": "customer-docs/passport_valid.png"
  },
  "model": { "provider": "Azure OpenAI", "deployment": "gpt-4o-mini" },
  "output": {
    "result": {
      "riskScore": 10, "level": "LOW",
      "reasons": [ "No sanctions found", "Low suspicion of fraud" ],
      "recommendation": "Continue monitoring with periodic reviews."
    },
    "breakdown": { "sanctions": 0, "doc": 30, "fraud": 10 }
  }
}
```

---

## Individual Agent Endpoints

### 1) Extract (Document Intelligence)

**POST** `/api/agents/extract`

```json
{ "resourcePath": "customer-docs/passport_valid.png" }
```

*or*

```json
{ "docUrl": "https://public-host/passport.png" }
```

### 2) Screen (Sanctions)

**POST** `/api/agents/screen`

```json
{ "name": "JANE DOE", "birthDate": "1992-04-12" }
```

### 3) Fraud (LLM triage)

**POST** `/api/agents/fraud`

```json
{
  "query": "Highlight risky patterns (velocity, structuring, threshold-skirting) in the last 6 months.",
  "transactions": [
    { "ts":"2025-02-10T09:12:00Z","amt":9800,"country":"US","channel":"cash_deposit" },
    { "ts":"2025-02-10T12:05:00Z","amt":9950,"country":"US","channel":"cash_deposit" },
    { "ts":"2025-02-11T08:00:00Z","amt":15000,"country":"RU","channel":"wire_out" }
  ]
}
```

### 4) Risk (fusion)

**POST** `/api/agents/risk`

```json
{ "sanctionsContext": "...", "docSignals": "...", "fraudSignals": "..." }
```

> These agent endpoints are handy for debugging and for showing each step in isolation during a demo.

---

## Sanctions Index Seeding (Optional)

If your repo includes a loader (e.g., `LoaderController` + `AzureSearchLoaderService`):

**POST** `http://localhost:8080/api/load/sanctions-to-search`

```json
{ }
```

**Response:** `{"upserted": <N>, "status": "ok"}`

> Ensure your index schema marks at least one string field as **`searchable: true`**; otherwise queries that use `search=*` will fail.

---


## Optional: Vector DB with pgvector

* Run Postgres + pgvector via Docker.
* Upsert embeddings of internal policies/FAQs or sanctions data.
* Do k‑NN queries (`<=>`) with IVFFLAT indexes for fast retrieval.
* You can integrate this as an alternative to Azure Search for certain RAG scenarios.

---

## Security Notes

* Do **not** commit secrets. Use environment variables, Azure Key Vault, or Managed Identity.
* This is a **demo**; real KYC/KYB requires audited sources, controls, and governance.

---

## License

Demo/sample code. Adapt to your organization’s license as needed.
