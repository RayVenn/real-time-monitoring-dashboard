package com.monitor;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink sink that writes {@link LatencyMetric} records to InfluxDB 2.x.
 *
 * <p>Configuration is read from environment variables at runtime:
 * <ul>
 *   <li>{@code INFLUXDB_URL}    — InfluxDB server URL (default: {@code http://localhost:8086})</li>
 *   <li>{@code INFLUXDB_TOKEN}  — API token (required)</li>
 *   <li>{@code INFLUXDB_ORG}    — Organisation name (required)</li>
 *   <li>{@code INFLUXDB_BUCKET} — Destination bucket (required)</li>
 * </ul>
 *
 * <p>Each {@link LatencyMetric} is written as an InfluxDB {@code Point}:
 * <pre>
 *   measurement : avg_latency
 *   tag         : dst_ip = &lt;destination IP&gt;
 *   field       : avg_rtt_us = &lt;average RTT in microseconds&gt;
 *   timestamp   : window_end_ms (millisecond precision)
 * </pre>
 *
 * <p>The {@link InfluxDBClient} is lazily initialised on the first call to
 * {@link #invoke} (and also in {@link #open}) so that the field can be
 * declared {@code transient} — Flink serializes sink instances across the
 * network to task managers, and the client is not serializable.
 */
public class InfluxDBSink extends RichSinkFunction<LatencyMetric> {

    private static final Logger LOG = LoggerFactory.getLogger(InfluxDBSink.class);

    // Not serializable — recreated on each task manager after deserialization.
    private transient InfluxDBClient influxDBClient;
    private transient WriteApiBlocking writeApi;

    // Connection parameters — read from env vars, stored as plain fields so
    // they survive Java serialization when Flink ships the operator graph.
    private String url;
    private String token;
    private String org;
    private String bucket;

    /**
     * Called once per task manager when the operator is initialized.
     * Reads configuration from environment variables and opens the client.
     */
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        initClientFromEnv();
    }

    /**
     * Writes a single {@link LatencyMetric} to InfluxDB.
     *
     * <p>If the client was not initialized in {@link #open} (e.g. after
     * deserialization without a subsequent {@code open} call in some Flink
     * versions), it is lazily created here.
     *
     * @param metric  the aggregated latency result for one dst_ip + window
     * @param context Flink sink context (unused)
     */
    @Override
    public void invoke(LatencyMetric metric, Context context) throws Exception {
        // Lazy init guard — defensive in case open() was not called.
        if (influxDBClient == null) {
            initClientFromEnv();
        }

        Point point = Point
                .measurement("avg_latency")
                .addTag("dst_ip", metric.getDst_ip())
                .addField("avg_rtt_us", metric.getAvg_rtt_us())
                // InfluxDB timestamp precision is set to MILLISECONDS; the
                // Point API accepts a long in the declared precision.
                .time(metric.getWindow_end_ms(), WritePrecision.MS);

        writeApi.writePoint(bucket, org, point);

        LOG.debug("Written to InfluxDB: dst_ip={} avg_rtt_us={} window_end_ms={}",
                metric.getDst_ip(), metric.getAvg_rtt_us(), metric.getWindow_end_ms());
    }

    /**
     * Closes the InfluxDB client when the operator is torn down.
     */
    @Override
    public void close() throws Exception {
        if (influxDBClient != null) {
            influxDBClient.close();
            influxDBClient = null;
            writeApi = null;
        }
        super.close();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void initClientFromEnv() {
        url    = getEnvOrDefault("INFLUXDB_URL",    "http://localhost:8086");
        token  = getEnvOrDefault("INFLUXDB_TOKEN",  "");
        org    = getEnvOrDefault("INFLUXDB_ORG",    "");
        bucket = getEnvOrDefault("INFLUXDB_BUCKET", "");

        LOG.info("Connecting to InfluxDB at {} (org={}, bucket={})", url, org, bucket);

        influxDBClient = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
        writeApi = influxDBClient.getWriteApiBlocking();
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
