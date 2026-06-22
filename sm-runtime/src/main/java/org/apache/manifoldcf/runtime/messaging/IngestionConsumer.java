package org.apache.manifoldcf.runtime.messaging;

import org.apache.manifoldcf.core.connector.OutputConnector;
import org.apache.manifoldcf.core.document.RepositoryDocument;
import org.apache.manifoldcf.runtime.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;

@Component
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);
    private final OutputConnector outputConnector;

    public IngestionConsumer(OutputConnector outputConnector) {
        this.outputConnector = outputConnector;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_NAME, groupId = "spring-manifold-vector-group")
    public void consume(IngestionMessage message) {
        log.info("Received document message from Kafka: {}", message.documentId());
        try {
            URI fileUri = new URI(message.uri());
            
            // Resolve local filesystem stream (Claim Check Pattern)
            try (InputStream contentStream = Files.newInputStream(Paths.get(fileUri))) {
                RepositoryDocument repoDoc = new RepositoryDocument(
                    message.documentId(),
                    message.uri(),
                    contentStream,
                    message.metadata(),
                    message.acl(),
                    Instant.parse(message.lastModified())
                );
                
                // Send document to output connector (Vector Store) and block for completion
                outputConnector.send(repoDoc).block();
                log.info("Successfully processed document from Kafka: {}", message.documentId());
            }
        } catch (Exception e) {
            log.error("Failed to process Kafka ingestion message: {}", message.documentId(), e);
        }
    }
}
