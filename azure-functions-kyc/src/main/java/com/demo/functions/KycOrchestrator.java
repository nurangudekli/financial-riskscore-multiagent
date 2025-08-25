package com.demo.functions;

import com.microsoft.azure.functions.annotation.*;
import com.microsoft.durabletask.*;
import java.util.*;

public class KycOrchestrator {
  static class Step { public String name; public String result; }

  @FunctionName("KycOrchestrator")
  public String run(@DurableOrchestrationTrigger(name = "ctx") TaskOrchestrationContext ctx) {
    Map<String,Object> input = ctx.getInput(Map.class);

    List<Task<Step>> fanOut = new ArrayList<>();
    fanOut.add(ctx.callActivity("CallExtractor", input, Step.class));
    fanOut.add(ctx.callActivity("CallScreening", input, Step.class));
    fanOut.add(ctx.callActivity("CallFraud", input, Step.class));

    List<Step> results = ctx.allOf(fanOut).await();

    Map<String,Object> riskInput = new HashMap<>();
    for (Step s : results) riskInput.put(s.name, s.result);

    Step risk = ctx.callActivity("CallRiskLc4j", riskInput, Step.class).await();
    return risk.result;
  }
}
