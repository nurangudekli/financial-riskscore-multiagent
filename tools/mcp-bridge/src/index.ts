import fetch from "node-fetch";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const server = new Server({ name: "kyc-multiagent", version: "1.0.0" }, { capabilities: { tools: {} } });
async function post(path: string, body: any){
  const base = process.env.API_BASE || "http://localhost:8080";
  const res = await fetch(`${base}${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)});
  return await res.text();
}
server.tool("ingest", { description: "Ingest PDF into vector store", inputSchema: { type: "object", properties: { url:{type:"string"}, path:{type:"string"}, collection:{type:"string"} }, anyOf:[{required:["url"]},{required:["path"]}] } }, async (args) => ({ content: [{ type: "text", text: await post("/api/ingest", args) }] }));
server.tool("agent_extract", { description: "Extractor agent", inputSchema: { type: "object", properties: { documentText:{type:"string"} }, required:["documentText"] } }, async (a) => ({ content:[{type:"text", text: await post("/api/agents/extract", a)}]}));
server.tool("agent_screen", { description: "Screening agent", inputSchema: { type: "object", properties: { name:{type:"string"}, birthDate:{type:"string"} }, required:["name"] } }, async (a) => ({ content:[{type:"text", text: await post("/api/agents/screen", a)}]}));
server.tool("agent_fraud", { description: "Fraud triage agent", inputSchema: { type: "object", properties: { query:{type:"string"} }, required:["query"] } }, async (a) => ({ content:[{type:"text", text: await post("/api/agents/fraud", a)}]}));
server.tool("agent_risk", { description: "Risk scoring agent", inputSchema: { type: "object", properties: { sanctionsContext:{}, docSignals:{}, fraudSignals:{} } } }, async (a) => ({ content:[{type:"text", text: await post("/api/agents/risk", a)}]}));
server.tool("kyc_orchestrate", { description: "Fan-out/fan-in orchestration", inputSchema: { type: "object", properties: { name:{type:"string"}, birthDate:{type:"string"}, question:{type:"string"}, documentText:{type:"string"} }, required:["name","documentText"] } }, async (a) => ({ content:[{type:"text", text: await post("/api/kyc/start", a)}]}));
await server.connect(new StdioServerTransport());
