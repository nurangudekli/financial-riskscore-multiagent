package com.demo.rag.agents;

import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RiskAgent {

    private final AzureOpenAiChatModel model;
    private final ObjectMapper om;
    private final String deployment;

    public RiskAgent(ObjectMapper objectMapper) {
        String endpoint   = System.getenv("AZURE_OPENAI_ENDPOINT");
        String apiKey     = System.getenv("AZURE_OPENAI_API_KEY");
        this.deployment   = System.getenv().getOrDefault("AZURE_OPENAI_DEPLOYMENT", "gpt-4o-mini");

        this.model = AzureOpenAiChatModel.builder()
                .endpoint(endpoint)
                .apiKey(apiKey)
                .deploymentName(deployment)
                .temperature(0.1)
                .build();

        this.om = objectMapper; // Spring'in JSR-310 yüklü mapper'ı
    }

    /** Eski minimal sürümü korumak istersen: */
    public String score(String sanctionsContext, String docSignalsJson, String fraudJson) {
        return scoreDetailed(sanctionsContext, docSignalsJson, fraudJson);
    }

    /** Yeni, okunur "zarf" JSON döner. */
    public String scoreDetailed(String sanctionsContext, String docSignalsJson, String fraudJson) {
        // 1) LLM'den breakdown'lı sonuç iste
        String sys =
                "SYSTEM: You are a KYC/KYX risk scorer. " +
                        "Return STRICT JSON only with the following shape:\n" +
                        "{\n" +
                        "  \"result\": {\"riskScore\": 0-100, \"level\": \"LOW|MEDIUM|HIGH\", \"reasons\": [string], \"recommendation\": string},\n" +
                        "  \"breakdown\": {\"sanctions\": number, \"doc\": number, \"fraud\": number}\n" +
                        "}\n" +
                        "No prose, no extra keys.";

        String usr =
                "SanctionsContext=" + sanctionsContext + "\n" +
                        "DocSignals=" + docSignalsJson + "\n" +
                        "FraudSignals=" + fraudJson + "\n" +
                        "Calibrate: sanctions up to ~60, doc up to ~30, fraud up to ~10. " +
                        "If evidence is weak, lower the respective component.\n" +
                        "Return JSON only.";

        String llmRaw = model.chat(sys + "\n" + usr);

        // 2) LLM sonucunu JSON'a parse et (hata halinde olduğu gibi bırak)
        JsonNode llmNode;
        try {
            llmNode = om.readTree(llmRaw);
        } catch (Exception e) {
            // LLM JSON'u bozuksa, en azından raw döndür
            return wrapEnvelope(sanctionsContext, docSignalsJson, fraudJson, Map.of("raw", llmRaw));
        }

        Map<String, Object> llmMap = om.convertValue(llmNode, new TypeReference<Map<String,Object>>(){});
        return wrapEnvelope(sanctionsContext, docSignalsJson, fraudJson, llmMap);
    }

    // ---------- helpers ----------

    /** Zarfı kur: inputs + model + output */
    private String wrapEnvelope(String sanctionsContext, String docSignalsJson, String fraudJson,
                                Map<String, Object> llmOutput) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        envelope.put("inputs", Map.of(
                "sanctionsContext", parseOrRaw(sanctionsContext),
                "docSignals",       parseOrRaw(docSignalsJson),
                "fraudSignals",     parseOrRaw(fraudJson)
        ));

        envelope.put("model", Map.of(
                "provider", "Azure OpenAI",
                "deployment", this.deployment
        ));

        envelope.put("output", llmOutput);

        try {
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
        } catch (Exception e) {
            try { return om.writeValueAsString(envelope); }
            catch (Exception ignored) { return "{\"error\":\"serialization-failed\"}"; }
        }
    }

    private Object parseOrRaw(String maybeJson) {
        if (maybeJson == null) return null;
        try {
            return om.readValue(maybeJson, new TypeReference<Map<String,Object>>(){});
        } catch (Exception e) {
            try {
                return om.readValue(maybeJson, new TypeReference<Object>(){}); // array vs. olabilir
            } catch (Exception ex) {
                return Map.of("raw", maybeJson);
            }
        }
    }
}
