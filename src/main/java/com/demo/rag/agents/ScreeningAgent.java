package com.demo.rag.agents;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.SearchDocument;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchMode;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.util.SearchPagedIterable;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

@Service
public class ScreeningAgent {

    private final SearchClient client;
    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    public ScreeningAgent() {
        String endpoint = getenvOrThrow("SEARCH_ENDPOINT");
        String index    = Optional.ofNullable(System.getenv("SEARCH_INDEX")).orElse("sanctions-demo");
        String key      = getenvOrThrow("SEARCH_API_KEY");
        this.client = new SearchClientBuilder()
                .endpoint(endpoint)
                .indexName(index)
                .credential(new AzureKeyCredential(key))
                .buildClient();
    }

    public String sanctionsScreen(String name, String dob) {
        Map<String,Object> out = new LinkedHashMap<>();
        List<Map<String,Object>> hits = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        if (name == null || name.isBlank()) {
            out.put("count", 0);
            out.put("matches", hits);
            out.put("reasons", List.of("name missing"));
            return toJson(out);
        }

        String searchText = "\"" + name.trim() + "\""; // phrase-like
        String filter = null;
        if (dob != null && !dob.isBlank()) {
            // birthDate field'i şema önerimizde STRING'ti:
            filter = "birthDate eq '" + dob.trim() + "'";
        } else {
            reasons.add("dob missing -> weak screening");
        }

        SearchOptions opts = new SearchOptions()
                .setSearchFields("name","aliases")
                .setQueryType(QueryType.SIMPLE)
                .setSearchMode(SearchMode.ALL)
                .setIncludeTotalCount(true)
                .setTop(10);

        if (filter != null) opts.setFilter(filter);

        SearchPagedIterable results = client.search(searchText, opts, Context.NONE);

        int strong = 0, weak = 0;
        String normQ = normalize(name);

        results.forEach(r -> {
            SearchDocument d = r.getDocument(SearchDocument.class);
            String hitName = asStr(d.get("name"));
            String hitDob  = asStr(d.get("birthDate"));

            double sim = jw.apply(normQ, normalize(hitName));
            boolean dobMatch = (dob != null && !dob.isBlank() && dob.equals(hitDob));
            boolean strongMatch = dobMatch && sim >= 0.92;

            Map<String,Object> row = new LinkedHashMap<>();
            row.put("score", r.getScore());
            row.put("nameSimilarity", round(sim));
            row.put("dobMatch", dobMatch);
            row.put("strength", strongMatch ? "strong" : "weak");

            // sadece gerekli alanları döndür (tüm doc'u değil)
            Map<String,Object> doc = new LinkedHashMap<>();
            doc.put("id",        d.get("id"));
            doc.put("name",      hitName);
            doc.put("birthDate", hitDob);
            doc.put("country",   d.get("country"));
            doc.put("list",      d.get("list"));
            doc.put("program",   d.get("program"));
            doc.put("aliases",   d.get("aliases"));
            row.put("doc", doc);

            hits.add(row);
            if (strongMatch) { /* captured in outer scope via array hack if needed */ }
        });

        // strong/weak say: (foreach içinde final olmayan sayaç güncellemek için yeniden geç)
        for (Map<String,Object> h : hits) {
            if ("strong".equals(h.get("strength"))) strong++; else weak++;
        }

        // Özet + debug
        out.put("count", results.getTotalCount());
        out.put("strongCount", strong);
        out.put("weakCount", weak);
        out.put("matches", hits);
        out.put("reasons", reasons);
        out.put("debug", Map.of(
                "searchText", searchText,
                "filter", filter,
                "top", 10
        ));
        return toJson(out);
    }

    // --------- helpers ---------
    private static String getenvOrThrow(String k){
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing env: " + k);
        return v;
    }
    private static String asStr(Object o){ return o == null ? null : String.valueOf(o); }
    private static double round(double v){ return Math.round(v*1000.0)/1000.0; }

    private static String normalize(String s){
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}","");            // aksanları kaldır
        n = n.toUpperCase().replaceAll("[^A-Z ]"," ").replaceAll("\\s+"," ").trim();
        return n;
    }

    private static String toJson(Object o){
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"json-serialize\",\"message\":\""+e.getMessage()+"\"}";
        }
    }
}
