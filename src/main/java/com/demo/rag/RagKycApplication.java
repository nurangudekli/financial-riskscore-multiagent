package com.demo.rag;
import org.springframework.boot.SpringApplication;import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
@SpringBootApplication
@EnableAsync
public class RagKycApplication { public static void main(String[] args){ SpringApplication.run(RagKycApplication.class,args);} }
