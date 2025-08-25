// src/main/java/com/demo/rag/config/PdfConfig.java
package com.demo.rag.config;

import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PdfConfig {
  @Bean
  public PdfDocumentReaderConfig pdfDocumentReaderConfig() {
    return PdfDocumentReaderConfig.builder()
        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
        .withPagesPerDocument(1)
        .build();
  }
}
