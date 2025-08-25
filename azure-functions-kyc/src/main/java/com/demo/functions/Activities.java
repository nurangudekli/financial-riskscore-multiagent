package com.demo.functions;

import com.microsoft.azure.functions.annotation.*;
import java.net.*; import java.net.http.*; import java.time.Duration; import java.util.*;

public class Activities {
  static class Step { public String name; public String result; }
  private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private static String apiBase(){ return System.getenv().getOrDefault("APP_BASE", "http://localhost:8080"); }

  private static Step postJson(String path, String body, String name) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase()+path))
      .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    Step s = new Step(); s.name=name; s.result=resp.body(); return s;
  }

  @FunctionName("CallExtractor")
  public Step callExtractor(@ActivityTrigger(name="input") Map<String,Object> input) throws Exception {
    String docText = Objects.toString(input.getOrDefault("documentText",""));
    String payload = "{"documentText":""+docText.replace(""","\"")+""}";
    return postJson("/api/agents/extract", payload, "extractor");
  }

  @FunctionName("CallScreening")
  public Step callScreening(@ActivityTrigger(name="input") Map<String,Object> input) throws Exception {
    String name = Objects.toString(input.getOrDefault("name",""));
    String dob  = Objects.toString(input.getOrDefault("birthDate",""));
    String payload = "{"name":""+name.replace(""","\"")+"","birthDate":""+dob+""}";
    return postJson("/api/agents/screen", payload, "screening");
  }

  @FunctionName("CallFraud")
  public Step callFraud(@ActivityTrigger(name="input") Map<String,Object> input) throws Exception {
    String q = Objects.toString(input.getOrDefault("question","recent suspicious transactions"));
    String payload = "{"query":""+q.replace(""","\"")+""}";
    return postJson("/api/agents/fraud", payload, "fraud");
  }

  @FunctionName("CallRiskLc4j")
  public Step callRisk(@ActivityTrigger(name="input") Map<String,Object> input) throws Exception {
    String payload = "{"sanctionsMatches":"+input.get("screening")
      +","docSignals":"+input.get("extractor")
      +","fraudSignals":"+input.get("fraud")+"}";
    return postJson("/api/lc4j/risk-score", payload, "risk");
  }
}
