package com.demo.rag.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.io.InputStream;
import java.util.Set;

public class JsonSchemaValidator {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public static void validateOrThrow(String schemaPathOnClasspath, String json) {
    try (InputStream in = JsonSchemaValidator.class.getClassLoader().getResourceAsStream(schemaPathOnClasspath)) {
      if (in == null) throw new IllegalArgumentException("Schema not found: " + schemaPathOnClasspath);
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
      JsonSchema schema = factory.getSchema(in);
      JsonNode node = MAPPER.readTree(json);
      Set<ValidationMessage> errors = schema.validate(node);
      if (!errors.isEmpty()) throw new IllegalArgumentException("Schema validation failed: " + errors);
    } catch (Exception e) {
      throw new IllegalArgumentException("Validation error: " + e.getMessage(), e);
    }
  }
}
