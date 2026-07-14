/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.runtime.orchestrator;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import org.opencrawling.core.connector.RepositoryConnector;
import org.opencrawling.core.connector.OutputConnector;
import org.opencrawling.core.result.ScanResult;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.core.messaging.IngestionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.concurrent.StructuredTaskScope;
import java.util.List;
import java.util.ArrayList;

@Service
public class JobOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(JobOrchestrator.class);
    
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public JobOrchestrator(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @SuppressWarnings("preview")
    public void runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path) {
        runJob(repositoryConnector, outputConnector, path, null);
    }

    @SuppressWarnings("preview")
    public void runJob(RepositoryConnector repositoryConnector, OutputConnector outputConnector, String path, String transformationConnector) {
        log.info("Starting job for path: {} with transformation connector: {}", path, transformationConnector);
        
        String engine = "ollama";
        java.util.Map<String, String> config = java.util.Map.of("model", "mxbai-embed-large");

        if (transformationConnector != null && !transformationConnector.isBlank()) {
            try {
                java.util.List<org.opencrawling.runtime.api.ConnectorController.ConnectorDTO> connectors = 
                    org.opencrawling.runtime.api.PersistenceHelper.loadList("connectors.json", org.opencrawling.runtime.api.ConnectorController.ConnectorDTO.class, java.util.List.of());
                for (org.opencrawling.runtime.api.ConnectorController.ConnectorDTO conn : connectors) {
                    if (conn.name().equals(transformationConnector)) {
                        engine = conn.configuration().getOrDefault("engine", "ollama");
                        config = conn.configuration();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load connector configuration for {}: {}", transformationConnector, e.getMessage());
            }
        }

        final String finalEngine = engine;
        final java.util.Map<String, String> finalConfig = config;

        try (var scope = StructuredTaskScope.open()) {
            
            StructuredTaskScope.Subtask<List<ScanResult>> scanTask = scope.fork(() -> {
                List<ScanResult> results = new ArrayList<>();
                repositoryConnector.scan(path)
                    .flatMap(doc -> {
                        try {
                            String finalUri = doc.uri();
                            if (finalUri != null && !finalUri.startsWith("file:")) {
                                // Save stream to shared claims directory (Claim Check Pattern)
                                String sharedDir = resolveSharedDir();
                                File claimsDir = new File(sharedDir, "claims");
                                if (!claimsDir.exists()) {
                                    claimsDir.mkdirs();
                                }
                                String filename = doc.id() + "_" + doc.metadata().getOrDefault("name", List.of("document")).get(0);
                                filename = filename.replaceAll("[^a-zA-Z0-9.-]", "_");
                                File claimFile = new File(claimsDir, filename);
                                
                                try (InputStream in = doc.contentStream();
                                     OutputStream out = new FileOutputStream(claimFile)) {
                                    if (in != null) {
                                        in.transferTo(out);
                                        finalUri = claimFile.toURI().toString();
                                        log.info("Saved Alfresco document content to Claim Check file: {}", finalUri);
                                    } else {
                                        log.warn("Document content stream is null for id: {}", doc.id());
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to write Claim Check file for doc: {}", doc.id(), e);
                                }
                            }

                            IngestionMessage msg = new IngestionMessage(
                                doc.id(),
                                finalUri,
                                doc.metadata(),
                                doc.acl(),
                                doc.security(),
                                doc.lastModified().toString(),
                                transformationConnector,
                                finalEngine,
                                finalConfig
                            );
                            
                            // Publish document metadata to Kafka topic and wait for confirmation
                            kafkaTemplate.send(KafkaConfig.TOPIC_NAME, doc.id(), msg).get();
                            log.info("Published document reference to Kafka: {}", doc.id());
                            
                            return Mono.just((ScanResult) new ScanResult.Success(doc.id(), "1.0"));
                        } catch (Exception e) {
                            return Mono.just(new ScanResult.Failure(doc.id(), e));
                        }
                    })
                    .doOnNext(results::add)
                    .blockLast(); 
                return results;
            });
            
            scope.join();
            
            List<ScanResult> scanResults = scanTask.get();
            scanResults.forEach(r -> log.info(r.summarize()));
            
            log.info("Job scanning phase completed. Documents published to Kafka.");
            
        } catch (StructuredTaskScope.FailedException e) {
            log.error("Job execution failed due to subtask failure: ", e.getCause());
        } catch (Exception e) {
            log.error("Job execution failed: ", e);
        }
    }

    private String resolveSharedDir() {
        File dockerData = new File("/data");
        if (dockerData.exists() && dockerData.isDirectory() && dockerData.canWrite()) {
            return "/data";
        }
        File localData = new File("data");
        if (!localData.exists()) {
            localData.mkdirs();
        }
        return localData.getAbsolutePath();
    }
}
