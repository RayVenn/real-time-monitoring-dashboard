package com.monitor.cloudwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Subscribes to the {@code net-latency} and {@code net-retransmit} Kafka topics
 * and forwards raw data points to CloudWatch as two metrics:
 *
 * <ul>
 *   <li>{@code LatencyUs}        — RTT in microseconds, per {@code DstIp}</li>
 *   <li>{@code RetransmitCount}  — retransmission count per connection, per {@code DstIp}</li>
 * </ul>
 *
 * CloudWatch natively aggregates raw data points (p50/p90/p99/avg/min/max)
 * so no pre-aggregation is needed here.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code KAFKA_BROKERS} — bootstrap servers (default: {@code localhost:9092})</li>
 *   <li>{@code AWS_REGION}    — CloudWatch region  (default: {@code us-east-1})</li>
 * </ul>
 *
 * <p>AWS credentials are picked up automatically from the default credential chain
 * (env vars, ~/.aws/credentials, IAM role, etc.).
 */
public class CloudWatchExporter {

    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchExporter.class);

    private static final String NAMESPACE        = "Network Latency";
    private static final String TOPIC_LATENCY    = "net-latency";
    private static final String TOPIC_RETRANSMIT = "net-retransmit";
    private static final String CONSUMER_GROUP   = "cloudwatch-exporter";

    // CloudWatch allows up to 1000 data points per PutMetricData call.
    // We flush at 20 to keep latency low without hammering the API.
    private static final int BATCH_SIZE = 20;

    public static void main(String[] args) {
        String kafkaBrokers = getEnvOrDefault("KAFKA_BROKERS", "localhost:9092");
        String awsRegion    = getEnvOrDefault("AWS_REGION", "us-east-1");

        LOG.info("Starting CloudWatch exporter");
        LOG.info("Kafka brokers : {}", kafkaBrokers);
        LOG.info("AWS region    : {}", awsRegion);
        LOG.info("Namespace     : {}", NAMESPACE);

        ObjectMapper mapper = new ObjectMapper();

        CloudWatchClient cloudWatch = CloudWatchClient.builder()
                .region(Region.of(awsRegion))
                .build();

        Properties props = new Properties();
        props.put("bootstrap.servers", kafkaBrokers);
        props.put("group.id", CONSUMER_GROUP);
        props.put("key.deserializer",   "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset",  "latest");
        props.put("enable.auto.commit", "true");

        List<MetricDatum> buffer = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Arrays.asList(TOPIC_LATENCY, TOPIC_RETRANSMIT));
            LOG.info("Subscribed to topics: {}, {}", TOPIC_LATENCY, TOPIC_RETRANSMIT);

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        MetricDatum datum = toMetricDatum(mapper, record);
                        if (datum != null) {
                            buffer.add(datum);
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to parse record from {}: {}", record.topic(), record.value(), e);
                    }

                    if (buffer.size() >= BATCH_SIZE) {
                        flush(cloudWatch, buffer);
                    }
                }

                // Flush remainder after each poll so metrics aren't held back
                // when traffic is low.
                if (!buffer.isEmpty()) {
                    flush(cloudWatch, buffer);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MetricDatum toMetricDatum(ObjectMapper mapper,
                                             ConsumerRecord<String, String> record) throws Exception {
        if (TOPIC_LATENCY.equals(record.topic())) {
            NetworkEvent event = mapper.readValue(record.value(), NetworkEvent.class);
            return MetricDatum.builder()
                    .metricName("LatencyUs")
                    .dimensions(dstIpDimension(event.getDst_ip()))
                    .value((double) event.getRtt_us())
                    .unit(StandardUnit.MICROSECONDS)
                    .timestamp(Instant.ofEpochSecond(0, event.getTimestamp_ns()))
                    .build();
        }

        if (TOPIC_RETRANSMIT.equals(record.topic())) {
            RetransmitEvent event = mapper.readValue(record.value(), RetransmitEvent.class);
            return MetricDatum.builder()
                    .metricName("RetransmitCount")
                    .dimensions(dstIpDimension(event.getDst_ip()))
                    .value((double) event.getRetransmit_count())
                    .unit(StandardUnit.COUNT)
                    .timestamp(Instant.ofEpochSecond(0, event.getTimestamp_ns()))
                    .build();
        }

        return null;
    }

    private static Dimension dstIpDimension(String dstIp) {
        return Dimension.builder().name("DstIp").value(dstIp).build();
    }

    private static void flush(CloudWatchClient cloudWatch, List<MetricDatum> buffer) {
        try {
            cloudWatch.putMetricData(PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(buffer)
                    .build());
            LOG.debug("Flushed {} metrics to CloudWatch", buffer.size());
        } catch (Exception e) {
            LOG.error("Failed to put {} metrics to CloudWatch", buffer.size(), e);
        } finally {
            buffer.clear();
        }
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
