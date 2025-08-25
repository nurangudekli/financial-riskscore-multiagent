package com.demo.rag.load;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/load")
public class LoaderController {

    @Autowired private VectorStore vectorStore;
    @Autowired private AzureSearchLoaderService searchLoader;

    @PostMapping("/sanctions-to-search")
    public ResponseEntity<?> sanctionsToSearch() throws Exception {
        List<Map<String, Object>> rows = readCsvFromClasspath("sample-data/sanctions_simple_large.csv");
        searchLoader.ensureIndex();
        searchLoader.upsertMany(rows); // assuming this pushes to Azure AI Search

        Map<String, Object> payload = new HashMap<>();
        payload.put("upserted", rows.size());
        payload.put("status", "ok");
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/sanctions-to-vector")
    public ResponseEntity<?> sanctionsToVector() throws Exception {
        List<Map<String, Object>> rows = readCsvFromClasspath("sample-data/sanctions_simple_large.csv");
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(r.getOrDefault("name", ""))
                    .append("\nAliases: ").append(r.getOrDefault("aliases", ""))
                    .append("\nCountry: ").append(r.getOrDefault("country", ""))
                    .append("\nDOB: ").append(r.getOrDefault("birthDate", ""))
                    .append("\nProgram: ").append(r.getOrDefault("program", ""))
                    .append("\nList: ").append(r.getOrDefault("list", ""));
            Document d = new Document(sb.toString());
            d.getMetadata().put("collection", "sanctions");
            d.getMetadata().put("id", r.getOrDefault("id", ""));
            docs.add(d);
        }
        vectorStore.add(docs);

        Map<String, Object> payload = new HashMap<>();
        payload.put("ingested", docs.size());
        payload.put("collection", "sanctions");
        return ResponseEntity.ok(payload);
    }

    // ---------- Helpers ----------

    private List<Map<String, Object>> readCsvFromClasspath(String path) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path),
                                "CSV not found on classpath: " + path),
                        StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("CSV is empty: " + path);

            List<String> cols = parseCsvLine(header);
            List<Map<String, Object>> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> parts = parseCsvLine(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) {
                    String v = i < parts.size() ? parts.get(i) : "";
                    row.put(cols.get(i).trim(), v);
                }
                out.add(row);
            }
            return out;
        }
    }

    /** Simple CSV parser: handles commas inside quotes and doubled quotes ("") */
    private static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) return tokens;

        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // escaped quote
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        tokens.add(cur.toString());
        return tokens;
    }
}
