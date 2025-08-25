package com.demo.rag.web;

import com.demo.rag.agents.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentsController {

    private final ExtractorAgent extractor;
    private final ScreeningAgent screening;
    private final FraudAgent fraud;
    private final RiskAgent risk;

    public AgentsController(ExtractorAgent extractor, ScreeningAgent screening, FraudAgent fraud, RiskAgent risk) {
        this.extractor = extractor;
        this.screening = screening;
        this.fraud = fraud;
        this.risk = risk;
    }

    // --- Extract (artık docSignals JSON döner) ---
    @PostMapping(value = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> extract(@RequestBody Map<String, Object> body) {
        String url = body.get("docUrl") == null ? null : String.valueOf(body.get("docUrl"));
        String resource = body.get("resourcePath") == null ? null : String.valueOf(body.get("resourcePath"));

        try {
            if (url != null && !url.isBlank()) {
                return ResponseEntity.ok(extractor.inspect(url));                    // ✅ yeni yöntem
            } else if (resource != null && !resource.isBlank()) {
                return ResponseEntity.ok(extractor.inspectFromResource(resource));   // ✅ yeni yöntem
            } else {
                return ResponseEntity.badRequest().body("{\"error\":\"docUrl or resourcePath required\"}");
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"","\\\"");
            return ResponseEntity.internalServerError().body("{\"error\":\"extract-failed\",\"message\":\""+ msg +"\"}");
        }
    }

    // --- Screening unchanged ---
    @PostMapping(value = "/screen", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> screen(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", ""));
        String dob  = String.valueOf(body.getOrDefault("birthDate", ""));
        return ResponseEntity.ok(screening.sanctionsScreen(name, dob));
    }

    // --- Fraud unchanged (triage/analyze hangisini kullanıyorsan ona göre) ---
    @PostMapping(value = "/fraud", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> fraud(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(fraud.triage(String.valueOf(body.getOrDefault("query", "recent suspicious transactions"))));
    }

    // --- Risk unchanged (score veya scoreDetailed sende hangisi varsa) ---
    @PostMapping(value = "/risk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> risk(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(risk.score(
                String.valueOf(body.getOrDefault("sanctionsContext", "[]")),
                String.valueOf(body.getOrDefault("docSignals", "{}")),
                String.valueOf(body.getOrDefault("fraudSignals", "{}"))
        ));
    }
}
