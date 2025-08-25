package com.demo.rag.agents;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.models.VectorSearchOptions;
import com.azure.search.documents.models.VectorizedQuery;
import com.azure.search.documents.util.SearchPagedIterable;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.azure.AzureOpenAiEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FraudAgent
 *
 * Purpose:
 * - Standalone fraud triage agent. Does NOT depend on sanctions screening or document signals.
 * - Consumes an optional transactions JSON array and an analyst question/prompt.
 * - Optionally performs RAG over a Fraud Knowledge Base in Azure AI Search (if configured).
 *
 * Environment variables:
 * - AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, AZURE_OPENAI_DEPLOYMENT (chat)
 * - AZURE_OPENAI_EMBEDDING (embedding deployment, optional)
 * - SEARCH_ENDPOINT, SEARCH_API_KEY, FRAUD_INDEX (optional RAG over fraud KB)
 *
 * Output:
 * - Compact JSON string: {"suspicionLevel":"LOW|MEDIUM|HIGH","reasons":[],"references":[]}
 */
@Service
public class FraudAgent {

    private final AzureOpenAiChatModel model;
    // Optional RAG pieces (can be null if not configured)
    private final EmbeddingModel embed;
    private final SearchClient search;

    private final ObjectMapper om = new ObjectMapper();

    public FraudAgent() {
        // --- Azure OpenAI (chat) ---
        String aoaiEndpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String aoaiKey      = System.getenv("AZURE_OPENAI_API_KEY");
        String chatDeploy   = Optional.ofNullable(System.getenv("AZURE_OPENAI_DEPLOYMENT"))
                .orElse("gpt-4o-mini");

        this.model = AzureOpenAiChatModel.builder()
                .endpoint(aoaiEndpoint)
                .apiKey(aoaiKey)
                .deploymentName(chatDeploy)
                .temperature(0.35)
                .build();

        // --- Optional: embeddings for vector search over fraud KB ---
        EmbeddingModel embTmp = null;
        String embDeploy = System.getenv("AZURE_OPENAI_EMBEDDING"); // embedding deployment name
        if (embDeploy != null && !embDeploy.isBlank()) {
            embTmp = AzureOpenAiEmbeddingModel.builder()
                    .endpoint(aoaiEndpoint)
                    .apiKey(aoaiKey)
                    .deploymentName(embDeploy)
                    .build();
        }
        this.embed = embTmp;

        // --- Optional: Fraud KB index (NOT sanctions) ---
        SearchClient s = null;
        String searchEndpoint = System.getenv("SEARCH_ENDPOINT");
        String searchApiKey   = System.getenv("SEARCH_API_KEY");
        String fraudIndex     = System.getenv("FRAUD_INDEX"); // e.g., "fraud-kb"
        if (searchEndpoint != null && searchApiKey != null && fraudIndex != null && !fraudIndex.isBlank()) {
            s = new SearchClientBuilder()
                    .endpoint(searchEndpoint)
                    .credential(new AzureKeyCredential(searchApiKey))
                    .indexName(fraudIndex)
                    .buildClient();
        }
        this.search = s;
    }

    /** Back-compat: triage with only a question (no transactions). */
    public String triage(String question) {
        return triage(question, "[]");
    }

    /**
     * Main entry point: triage with question + transactions JSON (array of objects).
     * The agent remains fully independent from sanctions/document signals.
     */
    public String triage(String question, String transactionsJson) {
        // 0) Deterministic, explainable heuristics based solely on transactions
        List<String> foundSignals = analyzeTransactions(transactionsJson);

        // 1) Optional: retrieve domain knowledge (RAG) from Fraud KB
        String kb = runFraudSearchContext(question);

        // 2) LLM reasoning: produce a compact JSON decision
        String sys = ""
                + "SYSTEM: You are a bank fraud triage analyst. "
                + "Return ONLY compact JSON with the schema: "
                + "{\"suspicionLevel\":\"LOW|MEDIUM|HIGH\",\"reasons\":[],\"references\":[]}. "
                + "Ground your decision strictly in the provided transactions and knowledge context. "
                + "In 'reasons', reference concrete patterns (velocity, structuring, threshold-skirting, geo, device).";

        String usr = ""
                + "QUESTION:\n" + nullSafe(question) + "\n\n"
                + "HEURISTICS_FOUND:\n" + String.join(", ", foundSignals) + "\n\n"
                + "TRANSACTIONS_JSON:\n" + nullSafe(transactionsJson) + "\n\n"
                + "KNOWLEDGE_CONTEXT:\n" + kb + "\n\n"
                + "Return ONLY JSON.";

        String out = model.chat(sys + "\n" + usr);

        // 3) Optional schema validation (non-blocking)
        try {
            com.demo.rag.util.JsonSchemaValidator.validateOrThrow("schemas/fraud.schema.json", out);
        } catch (Throwable ignored) { }

        return out;
    }

    // ----------------- Heuristic analysis (transactions only) -----------------
    private List<String> analyzeTransactions(String transactionsJson) {
        List<String> signals = new ArrayList<>();
        if (transactionsJson == null || transactionsJson.isBlank()) return signals;

        try {
            List<Map<String, Object>> txs = om.readValue(transactionsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (txs.isEmpty()) return signals;

            List<Tx> parsed = new ArrayList<>();
            for (Map<String,Object> t : txs) {
                Tx x = new Tx();
                x.ts = parseInstant(t.get("ts"));            // ISO 8601 (Instant.parse)
                x.amt = toDouble(t.get("amt"));              // amount numeric
                x.country = asStr(t.get("country"));        // ISO 2 (e.g., US, GB)
                x.channel = asStr(t.get("channel"));        // cash_deposit, wire_out, card_purchase, ...
                x.device = asStr(t.get("device"));          // device identifier
                parsed.add(x);
            }
            parsed.sort(Comparator.comparing(a -> a.ts == null ? Instant.EPOCH : a.ts));

            // Near-threshold cash deposits: 9k–10k range
            long near10k = parsed.stream()
                    .filter(x -> "cash_deposit".equalsIgnoreCase(x.channel))
                    .filter(x -> x.amt >= 9000 && x.amt < 10000)
                    .count();
            if (near10k >= 3) signals.add("THRESHOLD_SKIRTING");

            // Velocity spike: >=5 transactions within any 24h window
            int maxIn24h = maxTransactionsInWindow(parsed, Duration.ofHours(24));
            if (maxIn24h >= 5) signals.add("VELOCITY_SPIKE");

            // Structuring: within 72h, >=3 near-10k cash deposits
            if (countNear10kInWindow(parsed, Duration.ofHours(72)) >= 3) {
                signals.add("STRUCTURING_PATTERN");
            }

            // Geo risk example: high-risk outbound wires to certain countries
            Set<String> highRisk = Set.of("IR","KP","SY","RU","BY","AF","YE");
            boolean geoRisk = parsed.stream()
                    .anyMatch(x -> "wire_out".equalsIgnoreCase(x.channel)
                            && x.country != null
                            && highRisk.contains(x.country.toUpperCase(Locale.ROOT)));
            if (geoRisk) signals.add("GEO_RISK");

            // Device hopping: >=3 distinct devices within 48h
            if (distinctDevicesInWindow(parsed, Duration.ofHours(48)) >= 3) {
                signals.add("DEVICE_HOPPING");
            }

        } catch (Exception ignore) {
            // Parsing failed; the LLM can still reason over the raw JSON string.
        }

        return signals;
    }

    // ----------------- Optional KB (RAG) -----------------
    private String runFraudSearchContext(String question) {
        if (search == null) return "";
        try {
            VectorizedQuery vq = null;
            if (embed != null && question != null && !question.isBlank()) {
                Embedding emb = embed.embed(question).content();
                List<Float> vector = new ArrayList<>(emb.vector().length);
                for (float v : emb.vector()) vector.add(v);
                vq = new VectorizedQuery(vector)
                        .setFields(new String[]{"embedding"})
                        .setKNearestNeighborsCount(5);
            }

            SearchOptions opts = new SearchOptions()
                    .setTop(5)
                    .setQueryType(QueryType.SIMPLE);

            if (vq != null) {
                opts.setVectorSearchOptions(new VectorSearchOptions()
                        .setQueries(new VectorizedQuery[]{ vq }));
            }

            SearchPagedIterable results = search.search(
                    (question == null || question.isBlank()) ? "*" : question, opts, null);

            StringBuilder sb = new StringBuilder();
            for (SearchResult r : results) {
                SearchDocument doc = r.getDocument(SearchDocument.class);
                Object title = doc.get("title");
                Object typ   = doc.get("typology");
                Object body  = doc.get("body");
                Object ref   = doc.get("ref");
                if (title != null) sb.append("Title: ").append(title).append("\n");
                if (typ   != null) sb.append("Typology: ").append(typ).append("\n");
                if (body  != null) sb.append("Note: ").append(body).append("\n");
                if (ref   != null) sb.append("Ref: ").append(ref).append("\n");
                sb.append("---\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ----------------- utils -----------------
    private static class Tx {
        Instant ts; double amt; String country; String channel; String device;
    }
    private static Instant parseInstant(Object o) {
        try { return (o == null) ? null : Instant.parse(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }
    private static double toDouble(Object o) {
        if (o == null) return 0;
        try { return (o instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }
    private static String asStr(Object o) { return o == null ? null : String.valueOf(o); }

    private static int maxTransactionsInWindow(List<Tx> txs, Duration win) {
        int n = txs.size(), best = 0, i = 0;
        for (int j = 0; j < n; j++) {
            Instant end = txs.get(j).ts;
            if (end == null) continue;
            while (i < j && txs.get(i).ts != null &&
                    Duration.between(txs.get(i).ts, end).compareTo(win) > 0) {
                i++;
            }
            best = Math.max(best, j - i + 1);
        }
        return best;
    }

    private static int countNear10kInWindow(List<Tx> txs, Duration win) {
        List<Tx> near = txs.stream()
                .filter(x -> "cash_deposit".equalsIgnoreCase(x.channel))
                .filter(x -> x.amt >= 9000 && x.amt < 10000)
                .filter(x -> x.ts != null)
                .collect(Collectors.toList());
        near.sort(Comparator.comparing(x -> x.ts));
        int n = near.size(), best = 0, i = 0;
        for (int j = 0; j < n; j++) {
            Instant end = near.get(j).ts;
            while (i < j && Duration.between(near.get(i).ts, end).compareTo(win) > 0) i++;
            best = Math.max(best, j - i + 1);
        }
        return best;
    }

    private static int distinctDevicesInWindow(List<Tx> txs, Duration win) {
        int n = txs.size(), best = 0, i = 0;
        Map<String,Integer> freq = new HashMap<>();
        for (int j = 0; j < n; j++) {
            Tx cur = txs.get(j);
            Instant end = cur.ts;
            if (end == null) continue;
            if (cur.device != null && !cur.device.isBlank()) {
                freq.merge(cur.device, 1, Integer::sum);
            }
            while (i < j && txs.get(i).ts != null &&
                    Duration.between(txs.get(i).ts, end).compareTo(win) > 0) {
                String dev = txs.get(i).device;
                if (dev != null && !dev.isBlank()) {
                    freq.merge(dev, -1, Integer::sum);
                    if (freq.get(dev) != null && freq.get(dev) <= 0) freq.remove(dev);
                }
                i++;
            }
            best = Math.max(best, freq.keySet().size());
        }
        return best;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
