package com.demo.rag.dto;

import java.util.List;

public record KycStartRequest(String name, String birthDate, String question, String documentText, List<TransactionDto> transactions) {

    public KycStartRequest {
        if (transactions == null) transactions = List.of();
    }
}
