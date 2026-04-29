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
 * Pipeline:
 *   MSK (net-latency topic)
 *     → event-time watermarks (5s tolerance)
 *     → key by dst_ip
 *     → 1-minute tumbling window
 *     → avg RTT aggregation
 *     → Lambda sink → CloudWatch
 *
 * Environment variables:
 *   KAFKA_BROKERS     MSK bootstrap brokers  (required)
 *   AWS_REGION        AWS region             (default: us-east-1)
 *   LAMBDA_FUNCTION   Lambda function name   (default: rtt-to-cloudwatch)
 */
public class LatencyJob {

    private static final Logger LOG = LoggerFactory.getLogger(LatencyJob.class);

    private static final String KAFKA_TOPIC    = "net-latency";
    private static final String CONSUMER_GROUP = "flink-latency-job";

    public static void main(String[] args) throws Exception {
        String kafkaBrokers   = env("KAFKA_BROKERS",   "localhost:9092");
        String region         = env("AWS_REGION",      "us-east-1");
        String lambdaFunction = env("LAMBDA_FUNCTION", "rtt-to-cloudwatch");

        LOG.info("Kafka brokers: {}", kafkaBrokers);
        LOG.info("Lambda function: {}", lambdaFunction);

        StreamExecutionEnvironment flinkEnv =
                StreamExecutionEnvironment.getExecutionEnvironment();
        flinkEnv.setParallelism(1);
        flinkEnv.enableCheckpointing(60_000L);

        // ── MSK / Kafka source ────────────────────────────────────────────────
        KafkaSource<NetworkEvent> kafkaSource = KafkaSource.<NetworkEvent>builder()
                .setBootstrapServers(kafkaBrokers)
                .setTopics(KAFKA_TOPIC)
                .setGroupId(CONSUMER_GROUP)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new NetworkEventDeserializationSchema())
                .build();

        DataStream<NetworkEvent> events = flinkEnv.fromSource(
                kafkaSource,
                WatermarkStrategy
                        .<NetworkEvent>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                        .withTimestampAssigner((e, t) -> e.getTimestampNs() / 1_000_000L),
                "msk-net-latency"
        );

        // ── 1-min tumbling window → avg RTT per dst_ip → Lambda ──────────────
        events
                .keyBy(NetworkEvent::getDstIp)
                .window(TumblingEventTimeWindows.of(Time.minutes(1)))
                .aggregate(new AvgRttAggregator(), new WindowResultFunction())
                .addSink(new LambdaSink(region, lambdaFunction))
                .name("lambda-cloudwatch-sink");

        flinkEnv.execute("network-latency-job");
    }

    // ── Deserialization ───────────────────────────────────────────────────────

    static class NetworkEventDeserializationSchema
            implements KafkaRecordDeserializationSchema<NetworkEvent> {

        private transient ObjectMapper mapper;

        @Override
        public void deserialize(ConsumerRecord<byte[], byte[]> record,
                                Collector<NetworkEvent> out) throws IOException {
            if (mapper == null) mapper = new ObjectMapper();
            if (record.value() == null) return;
            try {
                out.collect(mapper.readValue(record.value(), NetworkEvent.class));
            } catch (Exception e) {
                LOG.error("Failed to deserialise record: {}", new String(record.value()), e);
            }
        }

        @Override
        public TypeInformation<NetworkEvent> getProducedType() {
            return TypeInformation.of(NetworkEvent.class);
        }
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }
}
