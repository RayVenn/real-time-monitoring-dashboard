package com.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Flink streaming job — Network Latency Monitor.
 *
 * <p>Pipeline overview:
 * <ol>
 *   <li>Read JSON {@link NetworkEvent} records from Kafka topic {@code net-latency}.</li>
 *   <li>Assign event-time watermarks (bounded out-of-orderness: 5 seconds).
 *       The event timestamp comes from {@code timestamp_ns} (nanoseconds → milliseconds).</li>
 *   <li>Key by {@code dst_ip}.</li>
 *   <li>Apply a 1-minute tumbling event-time window.</li>
 *   <li>Aggregate: compute average RTT per window with {@link AvgRttAggregator}.</li>
 *   <li>Enrich with window metadata via {@link WindowResultFunction}.</li>
 *   <li>Sink aggregated {@link LatencyMetric} records to InfluxDB via {@link InfluxDBSink}.</li>
 * </ol>
 *
 * <p>Environment variables consumed:
 * <ul>
 *   <li>{@code KAFKA_BROKERS}   — Kafka bootstrap servers (default: {@code localhost:9092})</li>
 *   <li>{@code INFLUXDB_URL}    — InfluxDB URL           (default: {@code http://localhost:8086})</li>
 *   <li>{@code INFLUXDB_TOKEN}  — InfluxDB API token</li>
 *   <li>{@code INFLUXDB_ORG}    — InfluxDB organisation</li>
 *   <li>{@code INFLUXDB_BUCKET} — InfluxDB bucket</li>
 * </ul>
 */
public class LatencyJob {

    private static final Logger LOG = LoggerFactory.getLogger(LatencyJob.class);

    /** Kafka topic produced by the Rust pcap agent. */
    private static final String KAFKA_TOPIC = "net-latency";

    /** Consumer group id — change to reset offsets. */
    private static final String CONSUMER_GROUP = "flink-latency-job";

    public static void main(String[] args) throws Exception {

        // ------------------------------------------------------------------ //
        //  1. Execution environment
        // ------------------------------------------------------------------ //
        final StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        // Single partition in Kafka topic — match parallelism to avoid idle
        // subtasks holding the watermark at -∞ and stalling all windows.
        env.setParallelism(1);

        // Enable checkpointing every 60 seconds for fault tolerance.
        env.enableCheckpointing(60_000L);

        // ------------------------------------------------------------------ //
        //  2. Kafka source
        // ------------------------------------------------------------------ //
        String kafkaBrokers = getEnvOrDefault("KAFKA_BROKERS", "localhost:9092");
        LOG.info("Connecting to Kafka brokers: {}", kafkaBrokers);

        KafkaSource<NetworkEvent> kafkaSource = KafkaSource.<NetworkEvent>builder()
                .setBootstrapServers(kafkaBrokers)
                .setTopics(KAFKA_TOPIC)
                .setGroupId(CONSUMER_GROUP)
                // Start from the latest offset when no committed offset exists.
                // Change to OffsetsInitializer.earliest() to reprocess all messages.
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new NetworkEventDeserializationSchema())
                .build();

        // ------------------------------------------------------------------ //
        //  3. Watermark strategy
        //     - BoundedOutOfOrderness(5s): tolerate up to 5 seconds of late
        //       arrival before advancing the watermark.
        //     - Timestamp extractor: convert timestamp_ns (nanoseconds) to
        //       milliseconds, which is what Flink's event-time clock expects.
        // ------------------------------------------------------------------ //
        WatermarkStrategy<NetworkEvent> watermarkStrategy = WatermarkStrategy
                .<NetworkEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner((event, recordTimestamp) ->
                        // Nanoseconds → milliseconds (divide by 1,000,000)
                        event.getTimestamp_ns() / 1_000_000L);

        // ------------------------------------------------------------------ //
        //  4. Build the streaming pipeline
        // ------------------------------------------------------------------ //
        DataStream<NetworkEvent> events = env
                .fromSource(kafkaSource, watermarkStrategy, "Kafka: net-latency");

        events
                // Key the stream by destination IP; each dst_ip gets its own
                // independent window state.
                .keyBy(NetworkEvent::getDst_ip)

                // 1-minute tumbling event-time windows.
                // Windows fire when the watermark passes their end boundary.
                .window(TumblingEventTimeWindows.of(Time.minutes(1)))

                // Incremental aggregation (AvgRttAggregator) combined with
                // window metadata enrichment (WindowResultFunction).
                // Flink applies the aggregator per-event, then calls the
                // process function once with the final result.
                .aggregate(new AvgRttAggregator(), new WindowResultFunction())

                // Write each LatencyMetric to InfluxDB.
                .addSink(new InfluxDBSink())
                .name("InfluxDB Sink");

        // ------------------------------------------------------------------ //
        //  5. Execute
        // ------------------------------------------------------------------ //
        env.execute("Network Latency Monitor");
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /**
     * Deserializes Kafka {@link ConsumerRecord} bytes into {@link NetworkEvent}
     * objects using Jackson.
     *
     * <p>Implements {@link KafkaRecordDeserializationSchema} so that the full
     * Kafka record (key + value + metadata) is available. We only use the
     * value bytes here.
     */
    static class NetworkEventDeserializationSchema
            implements KafkaRecordDeserializationSchema<NetworkEvent> {

        // ObjectMapper is not serializable; mark transient and recreate lazily.
        private transient ObjectMapper objectMapper;

        @Override
        public void deserialize(ConsumerRecord<byte[], byte[]> record,
                                Collector<NetworkEvent> out) throws IOException {
            if (objectMapper == null) {
                objectMapper = new ObjectMapper();
            }
            if (record.value() == null) {
                LOG.warn("Received Kafka record with null value; skipping.");
                return;
            }
            try {
                NetworkEvent event = objectMapper.readValue(record.value(), NetworkEvent.class);
                out.collect(event);
            } catch (Exception e) {
                LOG.error("Failed to deserialize Kafka record: {}", new String(record.value()), e);
                // Skip malformed records rather than crashing the job.
            }
        }

        @Override
        public TypeInformation<NetworkEvent> getProducedType() {
            return TypeInformation.of(NetworkEvent.class);
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
