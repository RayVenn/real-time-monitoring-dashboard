# Real-Time Monitoring Dashboard

## Project Overview

Multi-module Gradle project containing the downstream consumers of the Rust pcap agent's Kafka topics.

Part of the `real-time-monitoring` workspace alongside the [Rust pcap agent](../real-time-monitoring-agent).

---

## Architecture

```
Rust pcap agent
  → Kafka
      ├── net-latency     (RTT events)
      └── net-retransmit  (retransmission events)
            │
            ├── flink-latency-job     → InfluxDB → Grafana
            └── cloudwatch-exporter   → AWS CloudWatch
```

---

## Workspace Structure

```
real-time-monitoring-dashboard/
├── CLAUDE.md                        # This file
├── README.md
├── settings.gradle                  # Root: includes both submodules
├── build.gradle                     # Shared: java 11, mavenCentral, jackson
├── gradlew / gradle/                # Shared Gradle wrapper (9.3.1)
├── docker-compose.yml               # InfluxDB 2.7 + Grafana
│
├── flink-latency-job/               # Flink streaming job
│   ├── build.gradle
│   └── src/main/java/com/monitor/
│       ├── LatencyJob.java          # Pipeline entry point
│       ├── NetworkEvent.java        # Kafka JSON POJO
│       ├── AvgRttAggregator.java    # Incremental avg RTT aggregation
│       ├── WindowResultFunction.java
│       ├── LatencyMetric.java
│       └── InfluxDBSink.java
│
└── cloudwatch-exporter/             # Plain Kafka consumer → CloudWatch
    ├── build.gradle
    └── src/main/java/com/monitor/cloudwatch/
        ├── CloudWatchExporter.java  # Main — poll loop + PutMetricData
        ├── NetworkEvent.java        # Kafka JSON POJO (net-latency)
        └── RetransmitEvent.java     # Kafka JSON POJO (net-retransmit)
```

---

## Modules

### flink-latency-job

Consumes `net-latency`, applies 1-minute tumbling windows, writes avg RTT per `dst_ip` to InfluxDB.

| Component | Library | Version |
|---|---|---|
| Streaming | Apache Flink | 1.18.1 |
| Kafka source | flink-connector-kafka | 3.1.0-1.18 |
| InfluxDB sink | influxdb-client-java | 7.1.0 |

### cloudwatch-exporter

Consumes `net-latency` and `net-retransmit`, forwards raw data points to CloudWatch.
CloudWatch handles all aggregation (p50/p90/p99/avg/min/max) natively.

| Component | Library | Version |
|---|---|---|
| Kafka consumer | kafka-clients | 3.7.0 |
| CloudWatch | aws-sdk-cloudwatch | 2.25.0 |

**Metrics published:**

| Metric | Topic | Unit | Dimension |
|---|---|---|---|
| `LatencyUs` | `net-latency` | Microseconds | `DstIp` |
| `RetransmitCount` | `net-retransmit` | Count | `DstIp` |

**Namespace:** `Network Latency`

---

## Kafka Message Formats

**`net-latency`:**
```json
{
  "src_ip": "192.168.0.166", "src_port": 55495,
  "dst_ip": "140.82.112.26", "dst_port": 443,
  "rtt_us": 31279, "timestamp_ns": 1772952115698113000
}
```

**`net-retransmit`:**
```json
{
  "src_ip": "192.168.0.166", "src_port": 55495,
  "dst_ip": "140.82.112.26", "dst_port": 443,
  "rto_us": 200000, "retransmit_count": 1, "timestamp_ns": 1772952115698113000
}
```

---

## Build & Run

### Prerequisites

```bash
# Java 11+
# Docker (for InfluxDB + Grafana)
# Kafka running on localhost:9092
# AWS credentials (~/.aws/credentials or env vars) — for cloudwatch-exporter
```

### Build all modules

```bash
./gradlew build
```

### Start infrastructure (InfluxDB + Grafana)

```bash
docker compose up -d
```

### Run flink-latency-job

```bash
KAFKA_BROKERS=localhost:9092 \
INFLUXDB_URL=http://localhost:8086 \
INFLUXDB_TOKEN=my-token \
INFLUXDB_ORG=my-org \
INFLUXDB_BUCKET=network_metrics \
./gradlew :flink-latency-job:run
```

### Run cloudwatch-exporter

```bash
KAFKA_BROKERS=localhost:9092 \
AWS_REGION=us-east-1 \
./gradlew :cloudwatch-exporter:run
```

---

## Environment Variables

| Variable | Module | Default | Description |
|---|---|---|---|
| `KAFKA_BROKERS` | both | `localhost:9092` | Kafka bootstrap servers |
| `AWS_REGION` | cloudwatch-exporter | `us-east-1` | CloudWatch region |
| `INFLUXDB_URL` | flink-latency-job | `http://localhost:8086` | InfluxDB URL |
| `INFLUXDB_TOKEN` | flink-latency-job | — | InfluxDB API token |
| `INFLUXDB_ORG` | flink-latency-job | — | InfluxDB organisation |
| `INFLUXDB_BUCKET` | flink-latency-job | — | InfluxDB bucket |

---

## Infrastructure Credentials (local dev only)

| Service | URL | Credentials |
|---|---|---|
| InfluxDB | http://localhost:8086 | admin / password123 |
| Grafana | http://localhost:3000 | admin / admin |
