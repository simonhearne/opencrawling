# Model Context Protocol (MCP) Integration

OpenCrawling features a built-in, secure **Model Context Protocol (MCP)** server (`McpVectorServer`) built with Spring AI. This allows LLMs (like Claude, ChatGPT, or local agents) to query the vector database directly while respecting document security.

---

## 🔒 Security & User-Level ACL Filters

Most vector search engines return all matches. In an enterprise system, this leads to security leaks (e.g., an LLM showing salary spreadsheets to a standard employee). 

The `McpVectorServer` resolves this by filtering results:
1.  **Identity Verification**: The client must supply the querying user's Active Directory SIDs or Group SIDs inside the request headers/metadata.
2.  **Vector Match Filtering**: When querying `pgvector`, the server appends an access check clause:
    ```sql
    SELECT * FROM vector_store 
    WHERE embedding <=> :query_vector < :threshold
      AND (acl_sids && :user_sids);
    ```
    This guarantees that the AI model only receives context documents that the querying user has permission to read.

---

## 🚀 Running the MCP Server

The MCP server is packaged within the core `oc-runtime` and communicates over the Server-Sent Events (SSE) protocol.

### 1. Start the Server
Run the Spring Boot application (ensure the `mcp` profile or property is enabled):
```bash
mvn spring-boot:run -pl oc-runtime -Dspring-boot.run.profiles=dev
```
The server exposes the MCP SSE endpoint at `http://localhost:8080/mcp/sse`.

### 2. Configure Claude Desktop
To test the secure server inside your local Claude Desktop application, add the server to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "opencrawling-secure-vector-mcp": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/client-cli",
        "sse",
        "http://localhost:8080/mcp/sse"
      ]
    }
  }
}
```

Once configured, Claude will display a plug icon representing the secure knowledge retrieval tools, allowing you to ask questions referencing secure company archives.
