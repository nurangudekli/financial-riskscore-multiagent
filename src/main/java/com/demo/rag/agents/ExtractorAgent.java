package com.demo.rag.agents;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.ai.documentintelligence.models.AnalyzeDocumentOptions;
import com.azure.ai.documentintelligence.models.AnalyzeResult;
import com.azure.ai.documentintelligence.models.AnalyzedDocument;
import com.azure.ai.documentintelligence.models.DocumentField;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.polling.SyncPoller;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;

@Service
public class ExtractorAgent {

    private static final String MODEL_ID = "prebuilt-idDocument";

    private final DocumentIntelligenceClient client;
    private final ObjectMapper om;

    public ExtractorAgent(ObjectMapper objectMapper) {
        String endpoint = getenvOrThrow("AI_DOCINT_ENDPOINT");
        String apiKey   = System.getenv("AI_DOCINT_KEY");
        endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length()-1) : endpoint;

        DocumentIntelligenceClientBuilder b = new DocumentIntelligenceClientBuilder()
                .endpoint(endpoint)
                .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS));

        if (apiKey != null && !apiKey.isBlank()) {
            b.credential(new AzureKeyCredential(apiKey));
        } else {
            b.credential(new DefaultAzureCredentialBuilder().build());
        }
        this.client = b.buildClient();
        this.om = objectMapper;
    }

    // --------- Public API (returns docSignals JSON) ---------

    /** Reads a document from classpath and returns docSignals JSON. */
    public String inspectFromResource(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return err("resource-not-found", "classpath:" + resourcePath);

            AnalyzeDocumentOptions opts = new AnalyzeDocumentOptions(BinaryData.fromStream(in));
            SyncPoller<?, AnalyzeResult> poller = client.beginAnalyzeDocument(MODEL_ID, opts);
            AnalyzeResult result = poller.getFinalResult();

            return toDocSignals(result, "classpath:" + resourcePath);

        } catch (HttpResponseException ex) {
            // service returned a real HTTP error (show status/body)
            return httpErr(ex);

        } catch (RuntimeException ex) {
            // LRO failures often surface here; unwrap HttpResponseException if present
            Throwable c = ex;
            while (c != null && !(c instanceof HttpResponseException)) c = c.getCause();
            if (c instanceof HttpResponseException hre) return httpErr(hre);
            return err("analyze-failed", ex.getMessage());
        } catch (Exception e) {
            return err("analyze-failed", e.getMessage());
        }
    }

    /** Reads a document from a publicly reachable URL and returns docSignals JSON. */
    public String inspect(String url) {
        try {
            AnalyzeDocumentOptions opts = new AnalyzeDocumentOptions(url);
            SyncPoller<?, AnalyzeResult> poller = client.beginAnalyzeDocument(MODEL_ID, opts);
            AnalyzeResult result = poller.getFinalResult();
            return toDocSignals(result, url);
        } catch (HttpResponseException ex) {
            return httpErr(ex); // has status/body
        } catch (RuntimeException ex) {
            // <- LRO failures often come here; unwrap cause if it's HttpResponseException
            Throwable c = ex.getCause();
            if (c instanceof HttpResponseException hre) {
                return httpErr(hre);
            }
            return err("analyze-failed", ex.getMessage());
        } catch (Exception e) {
            return err("analyze-failed", e.getMessage());
        }
    }

    // --------- AnalyzeResult → docSignals ---------
    private String toDocSignals(AnalyzeResult r, String docRef) {
        Map<String,Object> out = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        out.put("documentRef", docRef);

        if (r == null || r.getDocuments() == null || r.getDocuments().isEmpty()) {
            out.put("error", "no-document-parsed");
            return toJson(out);
        }

        AnalyzedDocument d = r.getDocuments().get(0);
        Map<String, DocumentField> f = d.getFields();

        // (A) Prefer visual fields; be generous with key names and formats
        String vName = firstNonBlank(
                contentOf(f, "FullName"),
                contentOf(f, "Name"),
                joinName(contentOf(f, "FirstName"), contentOf(f, "LastName")),
                joinName(contentOf(f, "GivenName"), contentOf(f, "Surname")),
                joinName(contentOf(f, "GivenNames"), contentOf(f, "Surname"))
        );
        String vDocNo = contentOf(f, "DocumentNumber");
        String vDob   = firstNonBlank(dateOf(f, "DateOfBirth"), dateOf(f, "BirthDate"));
        String vExp   = dateOf(f, "DateOfExpiration");

        // (B) Parse MRZ after visual extraction; only use as fallback
        String mrz = contentOf(f, "MachineReadableZone");
        String mrzLine1 = null, mrzLine2 = null;
        if (mrz != null) {
            String[] lines = mrz.split("\\R");
            if (lines.length >= 2) { mrzLine1 = lines[0]; mrzLine2 = lines[1]; }
        }
        MrzInfo mi = parseMrz(mrzLine1, mrzLine2);

        if (vName == null && mi.fullName != null) vName = mi.fullName;

        // Consistency checks ONLY when both sides are present
        boolean nameMismatch  = (vName  != null && mi.fullName != null) && !safeEqualsNorm(vName,  mi.fullName);
        boolean docNoMismatch = (vDocNo != null && mi.docNo    != null) && !safeEqualsNorm(vDocNo, mi.docNo);
        boolean dobMismatch   = (vDob   != null && mi.dobIso   != null) && !vDob.equals(mi.dobIso);

        // Expiration check
        boolean expired = false;
        if (vExp != null) {
            try { expired = LocalDate.parse(vExp).isBefore(LocalDate.now(ZoneOffset.UTC)); } catch (Throwable ignore) {}
        }

        // Simple quality/cropping hints
        boolean croppingHint = (r.getContent() != null && r.getContent().toLowerCase().contains("cropped"));

        // OCR confidence normalized (handle nullable Double)
        Double confObj = d.getConfidence();
        double conf = (confObj != null) ? confObj : 0.0;
        double quality = clamp(conf, 0, 1);

        // Reasons
        if (mrz != null && !mi.validChecks) {
            reasons.add("MRZ present but failed check-digit validation (ICAO 9303).");
        }
        if (mi.validChecks && (nameMismatch || docNoMismatch || dobMismatch)) {
            reasons.add("Inconsistency between MRZ and visual fields.");
        }
        if (expired) reasons.add("Document expired.");
        if (croppingHint) reasons.add("Cropped/partial frame detected.");

        // Output payload
        Map<String,Object> idInfo = new LinkedHashMap<>();
        idInfo.put("fullName", coalesce(vName, mi.fullName));
        idInfo.put("dob",      coalesce(vDob,  mi.dobIso));
        idInfo.put("docNo",    coalesce(vDocNo, mi.docNo));
        idInfo.put("country",  coalesce(contentOf(f, "CountryRegion"), contentOf(f, "Nationality")));
        out.put("idInfo", idInfo);

        out.put("mrzValid", mi.validChecks);
        out.put("identityMismatch", nameMismatch || docNoMismatch || dobMismatch);
        out.put("expired", expired);
        out.put("quality", quality);
        out.put("croppingHint", croppingHint);
        out.put("reasons", reasons);
        out.put("ok", true);

        return toJson(out);
    }


    // ---- small helpers for robust name extraction ----
    private static String firstNonBlank(String... vals){
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
    private static String joinName(String left, String right){
        if ((left == null || left.isBlank()) && (right == null || right.isBlank())) return null;
        if (left == null || left.isBlank()) return right.trim();
        if (right == null || right.isBlank()) return left.trim();
        return (left + " " + right).trim().replaceAll("\\s+", " ");
    }

    // --------- MRZ helpers ---------

    static class MrzInfo {
        String fullName;   // "GIVEN SURNAME"
        String docNo;      // 9 chars
        String dobIso;     // YYYY-MM-DD
        boolean validChecks;
    }

    private MrzInfo parseMrz(String l1, String l2) {
        MrzInfo m = new MrzInfo();
        if (l1 == null || l2 == null || l2.length() < 43) { m.validChecks = false; return m; }

        // TD3 layout - line 2 fields
        String docNo = safeSub(l2, 0, 9);
        char docNoCd = safeChar(l2, 9);
        String nat   = safeSub(l2, 11, 14);
        String dob   = safeSub(l2, 14, 20); char dobCd = safeChar(l2, 20);
        char sex     = safeChar(l2, 21);
        String exp   = safeSub(l2, 22, 28); char expCd = safeChar(l2, 28);
        String comp  = safeSub(l2, 0, 10) + safeSub(l2, 11, 21) + safeSub(l2, 22, 29) + safeSub(l2, 29, 42);
        char compCd  = safeChar(l2, 42);

        boolean okDoc = checkDigit(docNo, docNoCd);
        boolean okDob = checkDigit(dob, dobCd);
        boolean okExp = checkDigit(exp, expCd);
        boolean okCmp = checkDigit(comp, compCd);

        m.validChecks = okDoc && okDob && okExp && okCmp;
        m.docNo = stripFillers(docNo);

        // Name from line 1: P<XYZSURNAME<<GIVEN<<...
        String l = l1.startsWith("P<") ? l1.substring(2) : l1;
        String[] parts = l.split("<<");
        String left = parts.length > 0 ? parts[0] : "";
        String right = parts.length > 1 ? parts[1] : "";
        // left begins with 3-letter country, then surname letters
        String surname = left.length() > 3 ? left.substring(3) : left;
        surname = stripFillers(surname);
        String given = stripFillers(right).replace('<',' ').trim().replaceAll("\\s+"," ");
        m.fullName = (given + " " + surname).trim().replaceAll("\\s+"," ");

        // DOB → YYYY-MM-DD (YYMMDD; naive century guess for demo purposes)
        if (dob != null && dob.length()==6) {
            int yy = Integer.parseInt(dob.substring(0,2));
            String century = (yy >= 30) ? "19" : "20";
            m.dobIso = century + dob.substring(0,2) + "-" + dob.substring(2,4) + "-" + dob.substring(4,6);
        }
        return m;
    }

    private static boolean checkDigit(String data, char cd) {
        if (!Character.isDigit(cd)) return false;
        int[] w = {7,3,1};
        int sum = 0;
        for (int i=0;i<data.length();i++) sum += mrzVal(data.charAt(i)) * w[i%3];
        return (sum % 10) == (cd - '0');
    }
    private static int mrzVal(char c) {
        if (c=='<') return 0;
        if (c>='0' && c<='9') return c-'0';
        if (c>='A' && c<='Z') return 10 + (c-'A');
        return 0;
    }

    // --------- Utils ---------

    private static String getenvOrThrow(String k){
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing env: " + k);
        return v;
    }
    private static String contentOf(Map<String, DocumentField> f, String key){
        try {
            DocumentField df = f.get(key);
            if (df == null) return null;
            // Prefer content if present; else fall back to valueString
            String c = df.getContent();
            if (c != null && !c.isBlank()) return c;
            String vs = df.getValueString();
            return (vs != null && !vs.isBlank()) ? vs : null;
        } catch (Throwable ignore){ return null; }
    }
    private static String dateOf(Map<String, DocumentField> f, String key){
        try {
            DocumentField df = f.get(key);
            if (df == null) return null;
            if (df.getValueDate() != null) return df.getValueDate().toString(); // YYYY-MM-DD
            // bazı kimliklerde tarih string gelebilir
            String vs = df.getValueString();
            return (vs != null && vs.matches("\\d{4}-\\d{2}-\\d{2}")) ? vs : null;
        } catch (Throwable ignore){ return null; }
    }
    private static String stripFillers(String s){
        return s == null ? null : s.replace('<',' ').trim().replaceAll("\\s+"," ");
    }
    private static boolean safeEqualsNorm(String a, String b){
        if (a == null || b == null) return false;
        return normalize(a).equals(normalize(b));
    }
    private static String normalize(String s){
        return s==null? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
    }
    private static String coalesce(String... ss){
        for (String s: ss) if (s != null && !s.isBlank()) return s;
        return null;
    }
    private static double clamp(double v, double lo, double hi){ return Math.max(lo, Math.min(hi, v)); }

    /** Safe substring: returns empty string if indices are out of bounds. */
    private static String safeSub(String s, int start, int end){
        if (s == null) return "";
        int len = s.length();
        int a = Math.max(0, Math.min(start, len));
        int b = Math.max(a, Math.min(end, len));
        return (a >= b) ? "" : s.substring(a, b);
    }

    /** Safe char: returns '0' when out of bounds (neutral for check-digit math). */
    private static char safeChar(String s, int idx){
        if (s != null && idx >= 0 && idx < s.length()) return s.charAt(idx);
        return '0';
    }

    /** Formats HttpResponseException with status code and response body for easier diagnosis. */
    private String httpErr(HttpResponseException ex){
        String body = null;
        Integer status = null;
        try {
            if (ex.getResponse() != null) {
                status = ex.getResponse().getStatusCode();
                body = ex.getResponse().getBodyAsString().block();
            }
        } catch (Throwable ignore){}

        String msg = (status != null ? status + " " : "") + ex.getMessage()
                + (body != null ? (" :: " + body) : "");
        return err("analyze-failed", msg);
    }

    private String err(String code, String msg){
        String m = msg==null? "" : msg.replace("\"","\\\"");
        return "{\"error\":\""+code+"\",\"message\":\""+m+"\"}";
    }
    private String toJson(Object o){
        try { return om.writerWithDefaultPrettyPrinter().writeValueAsString(o); }
        catch (Exception e){ try { return om.writeValueAsString(o); } catch (Exception ignore){ return "{\"error\":\"json-serialize\"}"; } }
    }

    // Not used currently; kept for potential future use.
    @SuppressWarnings("unused")
    private static String guessContentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".pdf"))  return "application/pdf";
        return "application/octet-stream";
    }
}
