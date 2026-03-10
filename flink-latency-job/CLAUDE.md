# Flink Latency Job

## Project Overview

A Flink streaming job that consumes TCP network events from Kafka, computes 1-minute average RTT per destination IP, and writes results to InfluxDB for Grafana dashboards.

Part of the `real-time-monitoring` workspace alongside the Rust pcap agent.

---

## Architecture

```
Kafka (net-latency)
  → Flink (1-min tumbling window, avg RTT per dst_ip)
    → InfluxDB (measurement: avg_latency)
      → Grafana (time-series dashboard)
```

---

## Workspace Structure

```
flink-latency-job/
├── CLAUDE.md                          # This file
├── build.gradle                       # Gradle build (Shadow jar, Flink 1.18.1)
├── settings.gradle
├── docker-compose.yml                 # InfluxDB 2.7 + Grafana
└── src/main/java/com/monitor/
    ├── LatencyJob.java                # Main — pipeline entry point
    ├── NetworkEvent.java              # Kafka JSON POJO
    ├── LatencyMetric.java             # Output per window per key
    ├── AvgRttAggregator.java          # AggregateFunction: long[2] {sum, count}
    ├── WindowResultFunction.java      # ProcessWindowFunction: attaches window timestamp
    └── InfluxDBSink.java              # RichSinkFunction: writes to InfluxDB
```

---

## Tech Stack

| Component     | Library                        | Version      |
|---------------|--------------------------------|--------------|
| Streaming     | Apache Flink                   | 1.18.1       |
| Kafka source  | flink-connector-kafka          | 3.1.0-1.18   |
| InfluxDB sink | influxdb-client-java           | 7.1.0        |
| JSON          | jackson-databind               | 2.15.2       |
| Build         | Gradle + com.gradleup.shadow   | 8.3.3        |

---

## Kafka Message Format

**Topic:** `net-latency`

```json
{
  "src_ip": "192.168.0.166",
  "src_port": 55495,
  "dst_ip": "140.82.112.26",
  "dst_port": 443,
  "rtt_us": 31279,
  "timestamp_ns": 1772952115698113000
}
```

---

## InfluxDB Output Format

**Measurement:** `avg_latency`
**Tag:** `dst_ip`
**Field:** `avg_rtt_us`
**Timestamp:** window end boundary (ms precision)

---

## Pipeline Design

### Event Time vs Processing Time
Uses **event time** from `timestamp_ns` (nanoseconds → milliseconds).
Windows fire based on data timestamps, not wall clock — correct for Kafka backlogs and replays.

### Watermark Strategy
`forBoundedOutOfOrderness(5s)` — tolerates up to 5 seconds of late events before advancing the watermark and firing the window.

### Aggregation Pattern
`aggregate(AvgRttAggregator, WindowResultFunction)`:
- `AvgRttAggregator` — incremental, stores only `long[2]` {sum, count} per key/window
- `WindowResultFunction` — called once on window fire, attaches window end timestamp

### Parallelism
Set to **1** to match the single Kafka partition on `net-latency`.
If you increase Kafka partitions, increase `env.setParallelism()` to match.

### Checkpointing
Enabled every 60 seconds. Snapshots Kafka offsets + window accumulator state.
Enables automatic recovery without data loss on job crash.

---

## Build & Run

### Prerequisites

```bash
# Java 11+
# Docker (for InfluxDB + Grafana)
# Kafka running on localhost:9092 with topic net-latency
```

### Start infrastructure

```bash
docker compose up -d
```

### Build fat jar

```bash
./gradlew shadowJar
# → build/libs/flink-latency-job-1.0-SNAPSHOT.jar
```

### Run locally

```bash
KAFKA_BROKERS=localhost:9092 \
INFLUXDB_URL=http://localhost:8086 \
INFLUXDB_TOKEN=my-token \
INFLUXDB_ORG=my-org \
INFLUXDB_BUCKET=network_metrics \
nohup java -jar build/libs/flink-latency-job-1.0-SNAPSHOT.jar > flink-job.log 2>&1 &
```

### Environment Variables

| Variable          | Default                  | Description              |
|-------------------|--------------------------|--------------------------|
| `KAFKA_BROKERS`   | `localhost:9092`         | Kafka bootstrap servers  |
| `INFLUXDB_URL`    | `http://localhost:8086`  | InfluxDB server URL      |
| `INFLUXDB_TOKEN`  | (empty)                  | InfluxDB API token       |
| `INFLUXDB_ORG`    | (empty)                  | InfluxDB organisation    |
| `INFLUXDB_BUCKET` | (empty)                  | InfluxDB bucket          |

---

## Grafana Setup

1. Open http://localhost:3000 (admin / admin)
2. **Connections → Data sources → Add → InfluxDB**
   - Query language: `Flux`
   - URL: `http://influxdb:8086`
   - Org: `my-org` | Token: `my-token` | Bucket: `network_metrics`
3. Create a time series panel with:

```flux
from(bucket: "network_metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "avg_latency" and r._field == "avg_rtt_us")
  |> group(columns: ["dst_ip"])
```

---

## Key Implementation Notes

- **`parallelism=1`** — must match Kafka partition count; mismatched parallelism leaves idle source subtasks that hold the global watermark at `-∞`, stalling all windows forever.
- **`transient` fields in sinks** — `InfluxDBClient` is not serializable; declared `transient` and re-initialized in `open()` after Flink deserializes the operator on the task manager.
- **`mergeServiceFiles()`** in shadow jar — prevents `META-INF/services/` conflicts between bundled JARs (e.g. Jackson module auto-discovery).
- **`com.gradleup.shadow`** — use this plugin ID (not `com.github.johnrengelman.shadow`) for Gradle 9+ compatibility.
