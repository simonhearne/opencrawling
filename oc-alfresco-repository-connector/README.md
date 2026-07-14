# OpenCrawling - Alfresco Repository Connector

This module provides the repository connector for **Alfresco Content Services (ACS)**. It connects to the Alfresco REST API using the CMIS/Nodes endpoints, crawls directories recursively, downloads the document content streams, and integrates into the OpenCrawling ingestion pipeline.

## Configuration Parameters

When setting up the Alfresco Connector via the Admin UI or environment variables, use the following configuration keys:

| Parameter | UI Field / Configuration Key | Default Value | Description |
| :--- | :--- | :--- | :--- |
| **URL** | `url` | `http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1` | The base URL of the Alfresco REST API |
| **Username** | `username` | `admin` | Username for Basic authentication |
| **Password** | `password` | `admin` | Password for Basic authentication |
| **Batch Size** | `batchSize` | `100` | Number of child nodes to fetch per page during folder scans |
| **Crawl Scan Path** | `path` | `"-root-"` | The starting folder node UUID or relative path (e.g. `/Company Home/Sites/swsdp/documentLibrary`) |

---

## Verifying Ingestion in PGVector

Since OpenCrawling operates on a decoupled, asynchronous microservices architecture (utilizing Kafka and background vectorizers), documents are written to the database asynchronously. 

When remote Alfresco documents are crawled, they are cached as local file claims under `data/claims/` (Claim Check Pattern) and passed to the consumer.

You can verify that your Alfresco documents have been successfully ingested, chunked, and embedded into PGVector using the following PostgreSQL queries against the `postgres-vector` container.

### 1. Count Total Chunks Ingested from Alfresco
To get the total number of text chunks generated and stored from the Alfresco crawl:
```bash
docker exec -i postgres-vector psql -U opencrawling -d opencrawling -c "
SELECT count(*) as total_alfresco_chunks 
FROM vector_store 
WHERE metadata->>'uri' LIKE '%claims%';
"
```

### 2. List Unique Document Names Ingested
To verify the distinct files that have been successfully processed:
```bash
docker exec -i postgres-vector psql -U opencrawling -d opencrawling -c "
SELECT DISTINCT metadata->>'name' as document_name 
FROM vector_store 
WHERE metadata->>'uri' LIKE '%claims%';
"
```

### 3. Inspect Sample Chunks and Extracted Content
To view detailed records including metadata and previews of the extracted text content:
```bash
docker exec -i postgres-vector psql -U opencrawling -d opencrawling -c "
SELECT id, 
       substring(content from 1 for 100) as content_preview, 
       metadata->>'name' as file_name, 
       metadata->>'mimeType' as mime_type 
FROM vector_store 
WHERE metadata->>'uri' LIKE '%claims%' 
LIMIT 5;
"
```
