# System Architecture

OpenCrawling is designed for high-throughput, horizontal scalability. The platform is broken down into modular microservices that communicate asynchronously using **Apache Kafka**.

---

## 🏗️ Component Breakdown

The architecture is divided into the following primary layers:

### 1. Ingestion Runtime Bootstrap (`oc-runtime`)
The central orchestrator that schedules, runs, and monitors crawling jobs. It executes the crawlers, manages rate-limiting, and stores job states in PostgreSQL.

### 2. Core Ingestion Engine (`oc-core`)
Houses the main crawler SPI definitions. Once a crawler discovers a document, the core engine saves the file content to shared storage and publishes a lightweight pointer to the queue.

### 3. Decoupled Embedding Service (`oc-embedding-service`)
A stateless microservice that pulls text chunks from Kafka, requests vector embeddings from the configured AI engine (such as local Ollama instances or cloud OpenAI endpoints), and publishes the resulting vectors. This microservice can be scaled out instantly:
```bash
docker compose scale oc-embedding-service=3
```

### 4. Vector Store Writer (`VectorStoreWriterConsumer`)
Consumes embedded chunks and writes them directly to database outputs (such as PostgreSQL with `pgvector`, Elasticsearch, or Qdrant). It uses a stateless model to write raw vectors directly, bypassing model inference.

### 5. Secure Model Context Protocol Server (`McpVectorServer`)
Exposes knowledge retrieval tools to AI models and agents using the Model Context Protocol (MCP). It handles SSE-based queries, authenticates user identities, and filters search results by matching document ACL tokens.

---

## 🛞 Asynchronous Claim Check Pattern

To avoid clogging Kafka partitions with megabytes of binary file data (PDFs, Excel files, DOCX), OpenCrawling uses the **Claim Check Pattern**:

```
[Repository] ──(Crawl)──> [Crawler Engine] ──(Save Raw)──> [Shared Storage]
                                  │
                       (Publish IngestionMessage)
                                  │
                                  ▼
                        [Kafka Ingestion Topic]
                                  │
                       (Consume IngestionMessage)
                                  │
                                  ▼
               [IngestionConsumer (Apache Tika Text Extractor)]
                                  │
                           (Read from Storage)
                                  │
                     (Publish ChunkMessages to Kafka)
                                  │
                                  ▼
                       [Kafka Chunks Topic]
                                  │
                        [Embedding Service]
                                  │
                        (Generate Vector)
                                  │
                                  ▼
                       [Kafka Embedded Topic]
                                  │
                        [Vector Store Writer]
                                  │
                                  ▼
                             [pgvector]
```

1.  **Crawl & Check-In**: The repository connector discovers a document. Instead of sending the full payload, it saves the file to a shared file/object storage and publishes an `IngestionMessage` (the Claim Check) to Kafka.
2.  **Text Extraction & Chunking**: The `IngestionConsumer` reads the message, fetches the file from storage, extracts text using **Apache Tika**, splits it into semantic chunks, and publishes them to `opencrawling-chunks`.
3.  **Embedding Generation**: The `oc-embedding-service` consumes chunks, generates embedding vectors, and pushes them to `opencrawling-embedded`.
4.  **Vector Store Sync**: The `VectorStoreWriterConsumer` saves the vectors, metadata, and Security SIDs into the vector database.
