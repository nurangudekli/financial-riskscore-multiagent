package com.demo.rag.load;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.*;
import com.azure.search.documents.models.IndexDocumentsResult;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.azure.AzureOpenAiEmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.*;

/** Uploads CSV rows into Azure AI Search with vector index (no semantic config). */
@Service
public class AzureSearchLoaderService {

    private final String indexName;
    private final SearchIndexClient indexClient;
    private final SearchClient searchClient;

    // Optional embedding (if AOAI env is present)
    private final EmbeddingModel embedModel; // can be null
    private static final int VECTOR_DIM = 1536; // text-embedding-3-small

    public AzureSearchLoaderService() {
        String endpoint = reqEnv("SEARCH_ENDPOINT");
        String key = reqEnv("SEARCH_API_KEY");
        this.indexName = Optional.ofNullable(System.getenv("SEARCH_INDEX")).orElse("sanctions-demo");

        this.indexClient = new SearchIndexClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(key))
                .buildClient();

        this.searchClient = new SearchClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(key))
                .indexName(indexName)
                .buildClient();

        // Try to initialize AOAI embedding model; if any env is missing, keep null (no vectors)
        String aoaiEndpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String aoaiKey = System.getenv("AZURE_OPENAI_API_KEY");
        String embDeployment = Optional.ofNullable(System.getenv("AZURE_OPENAI_EMBEDDING"))
                .orElse("text-embedding-3-small");
        if (notBlank(aoaiEndpoint) && notBlank(aoaiKey) && notBlank(embDeployment)) {
            this.embedModel = AzureOpenAiEmbeddingModel.builder()
                    .endpoint(aoaiEndpoint)
                    .apiKey(aoaiKey)
                    .deploymentName(embDeployment)
                    .build();
        } else {
            this.embedModel = null;
        }
    }

    /** Create or update AI Search index with vector config (no semantic settings). */
    public void ensureIndex() {
        // --- Fields ---
        List<SearchField> fields = new ArrayList<>();

        fields.add(new SearchField("id", SearchFieldDataType.STRING)
                .setKey(true)
                .setFilterable(true));

        fields.add(new SearchField("name", SearchFieldDataType.STRING)
                .setSearchable(true));

        fields.add(new SearchField("aliases", SearchFieldDataType.STRING)
                .setSearchable(true));

        fields.add(new SearchField("country", SearchFieldDataType.STRING)
                .setFilterable(true)
                .setFacetable(true));

        fields.add(new SearchField("birthDate", SearchFieldDataType.STRING)
                .setFilterable(true));

        fields.add(new SearchField("program", SearchFieldDataType.STRING)
                .setSearchable(true));

        fields.add(new SearchField("list", SearchFieldDataType.STRING)
                .setFilterable(true));

        // Vector field (1536-dim, HNSW profile "vprofile")
        fields.add(new SearchField("embedding",
                SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                .setVectorSearchDimensions(VECTOR_DIM)
                .setVectorSearchProfileName("vprofile"));

        // --- Vector search (HNSW) ---
        VectorSearch vectorSearch = new VectorSearch()
                .setAlgorithms(Collections.singletonList(
                        new HnswAlgorithmConfiguration("hnsw-1536")))
                .setProfiles(Collections.singletonList(
                        new VectorSearchProfile("vprofile", "hnsw-1536")));

        // --- Create or update atomically ---
        SearchIndex index = new SearchIndex(indexName)
                .setFields(fields)
                .setVectorSearch(vectorSearch);

        indexClient.createOrUpdateIndex(index);
    }

    /** Upsert many rows (CSV records). Computes embeddings if AOAI is configured. */
    public int upsertMany(List<Map<String, Object>> rows) {
        List<SanctionDoc> docs = new ArrayList<>(rows.size());

        for (Map<String, Object> r : rows) {
            SanctionDoc d = new SanctionDoc();
            d.id = str(r.get("id"), UUID.randomUUID().toString());
            d.name = str(r.get("name"), "");
            d.aliases = str(r.get("aliases"), "");
            d.country = str(r.get("country"), "");
            d.birthDate = str(r.get("birthDate"), "");
            d.program = str(r.get("program"), "");
            d.list = str(r.get("list"), "");

            // If caller already provided embedding, use it; otherwise compute if embedModel exists
            Object pre = r.get("embedding");
            if (pre instanceof List) {
                //noinspection unchecked
                d.embedding = (List<Float>) pre;
            } else if (embedModel != null) {
                String content = (d.name + " " + d.aliases + " " + d.program).trim();
                try {
                    Embedding emb = embedModel.embed(content).content();
                    float[] vec = emb.vector();
                    d.embedding = new ArrayList<>(vec.length);
                    for (float v : vec) d.embedding.add(v);
                } catch (Throwable ignored) {
                    // leave embedding null if embedding failed
                }
            }

            docs.add(d);
        }

        IndexDocumentsResult res = searchClient.uploadDocuments(docs);
        // Optionally inspect res.getResults()
        return docs.size();
    }

    // ----------- POJO Azure Search will serialize -----------
    public static class SanctionDoc {
        public String id;
        public String name;
        public String aliases;
        public String country;
        public String birthDate;
        public String program;
        public String list;
        public List<Float> embedding; // optional
    }

    // ---------------- helpers ----------------
    private static String reqEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return v;
    }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String str(Object v, String def) { return v == null ? def : String.valueOf(v); }
}
