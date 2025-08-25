package com.demo.functions;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.durabletask.*;

public class StartHttp {
  @FunctionName("StartKyc")
  public HttpResponseMessage start(
      @HttpTrigger(name = "req", methods = {HttpMethod.POST}, authLevel = AuthorizationLevel.FUNCTION, route = "api/kyc/start") HttpRequestMessage<String> req,
      @DurableClientInput(name = "client") DurableTaskClient client) {
    String instanceId = client.scheduleNewOrchestrationInstance("KycOrchestrator", req.getBody());
    return req.createResponseBuilder(HttpStatus.ACCEPTED)
      .header("Location", client.createHttpManagementPayload(instanceId).getStatusQueryGetUri())
      .body("Started orchestration with ID=" + instanceId).build();
  }
}
