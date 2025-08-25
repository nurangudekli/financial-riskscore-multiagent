// com/demo/rag/dto/TransactionDto.java
package com.demo.rag.dto;

public record TransactionDto(
    String ts,       // ISO-8601, e.g. "2025-02-10T09:12:00Z"
    Double amt,      // amount
    String country,  // ISO-2, e.g. "US"
    String channel,  // e.g. "cash_deposit", "wire_out", "card_purchase"
    String device    // device/session id
) {}
