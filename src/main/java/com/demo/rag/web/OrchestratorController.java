package com.demo.rag.web;

import com.demo.rag.agents.ExtractorAgent;
import com.demo.rag.agents.FraudAgent;
import com.demo.rag.agents.RiskAgent;
import com.demo.rag.agents.ScreeningAgent;
import com.demo.rag.dto.KycStartRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/kyc")
public class OrchestratorController {

    private final ExtractorAgent extractor;
    private final ScreeningAgent screening;
    private final FraudAgent fraud;
    private final RiskAgent risk;
    private final TaskExecutor exec;
    private final ObjectMapper om;

    public OrchestratorController(
            ExtractorAgent extractor,
            ScreeningAgent screening,
            FraudAgent fraud,
            RiskAgent risk,
            TaskExecutor exec,
            ObjectMapper objectMapper
    ) {
        this.extractor = extractor;
        this.screening = screening;
        this.fraud = fraud;
        this.risk = risk;
        this.exec = exec;
        this.om = objectMapper;
    }

    @PostMapping("/start")
    public ResponseEntity<String> start(@RequestBody KycStartRequest req) {

        // 1) Extraction (docSignals) — URL ise inspect(url), değilse classpath'ten
        CompletableFuture<String> fExtract = CompletableFuture.supplyAsync(() -> {
            String docRef = req.documentText();
            if (docRef == null || docRef.isBlank()) {
                return "{\"error\":\"no-document\"}";
            }
            try {
                return docRef.startsWith("http")
                        ? extractor.inspect(docRef)
                        : extractor.inspectFromResource(docRef);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"","\\\"");
                return "{\"error\":\"analyze-failed\",\"message\":\""+ msg +"\"}";
            }
        }, r -> exec.execute(r));

        // 2) Fraud
        CompletableFuture<String> fFraud = CompletableFuture.supplyAsync(() ->
                        fraud.triage(
                                String.valueOf(req.question()),
                                toJsonSafe(req.transactions())
                        ),
                r -> exec.execute(r)
        );
        // 3) Screening — extraction -> identity seçimi -> screening
        CompletableFuture<String> fScreen = fExtract.thenApplyAsync(docSignals -> {
            NameDob id = pickIdentity(docSignals, req.name(), req.birthDate());
            return screening.sanctionsScreen(id.name, id.dob);
        }, r -> exec.execute(r));

        // 4) Hepsini birleştir → RiskAgent
        String finalJson = CompletableFuture.allOf(fExtract, fFraud, fScreen)
                .thenApply(v -> {
                    String docSignals = fExtract.join();
                    String fraudJson  = fFraud.join();
                    String sanctions  = fScreen.join();
                    String envelope   = risk.scoreDetailed(sanctions, docSignals, fraudJson);
                    // İsteğe bağlı: identityUsed + documentRef ekle
                    NameDob used = pickIdentity(docSignals, req.name(), req.birthDate());
                    return enrichEnvelope(envelope, used.name, used.dob, req.documentText(),
                            hasError(docSignals) ? "request" : "document");
                })
                .join();

        return ResponseEntity.ok(finalJson);
    }

    // -------- helpers --------

    private static String toJsonSafe(Object o) {
        if (o == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static class NameDob {
        final String name; final String dob;
        NameDob(String n, String d){ this.name = n; this.dob = d; }
    }

    /** docSignals içinden idInfo.fullName / idInfo.dob'u çek; yoksa body'dekilere düş */
    private NameDob pickIdentity(String docSignalsJson, String reqName, String reqDob) {
        try {
            JsonNode n = om.readTree(docSignalsJson);
            String dn = null, dd = null;
            if (n != null && n.has("idInfo")) {
                JsonNode id = n.get("idInfo");
                if (id.hasNonNull("fullName")) dn = id.get("fullName").asText();
                if (id.hasNonNull("dob"))      dd = id.get("dob").asText();
            }
            return new NameDob(coalesce(dn, reqName), coalesce(dd, reqDob));
        } catch (Exception ignore) {
            return new NameDob(reqName, reqDob);
        }
    }

    private boolean hasError(String json){
        try {
            JsonNode n = om.readTree(json);
            return n != null && n.has("error");
        } catch (Exception e) { return false; }
    }

    private String enrichEnvelope(String envelope, String name, String dob, String ref, String source) {
        try {
            JsonNode n = om.readTree(envelope);
            JsonNode inputs = n.with("inputs");
            ((com.fasterxml.jackson.databind.node.ObjectNode)inputs)
                    .putObject("identityUsed").put("name", name).put("dob", dob).put("source", source);
            ((com.fasterxml.jackson.databind.node.ObjectNode)inputs).put("documentRef", ref);
            return om.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            return envelope;
        }
    }

    private static String coalesce(String... ss){
        for (String s: ss) if (s != null && !s.isBlank()) return s;
        return null;
    }
}
