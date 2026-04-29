# Real-Time Monitoring Dashboard

## Project Overview

Multi-module Gradle project containing the downstream consumers of the Go pcap agent's MSK topics.

Part of the `real-time-monitoring` workspace alongside the [Go agent](../real-time-monitoring-agent).

---

## Architecture

```
Go pcap agent (monitor-go)
  → MSK (Amazon Managed Kafka)
      ├── net-latency     (RTT events)
      └── net-retransmit  (retransmission events)
            │
            ├── Amazon Managed Flink (flink-latency-job)
            │     → Lambda (rtt-to-cloudwatch)
            │       → CloudWatch  Network/Latency / AvgRttUs
            │
            └── cloudwatch-exporter (Java)
                  → CloudWatch  Network Latency / LatencyUs + RetransmitCount
                        │
                        └── CloudWatch Dashboard  +  Amazon Managed Grafana
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
├── docker-compose.yml               # InfluxDB 2.7 + Grafana (local dev only)
│
├── lambda/                          # Python Lambda: Flink → CloudWatch
│   └── handler.py                   # Receives LatencyMetric JSON, calls PutMetricData
│
├── flink-latency-job/               # Flink streaming job (Managed Flink in prod)
│   ├── build.gradle
│   └── src/main/java/com/monitor/
│       ├── LatencyJob.java          # Pipeline entry point
│       ├── NetworkEvent.java        # Kafka JSON POJO
│       ├── AvgRttAggregator.java    # Incremental avg RTT aggregation
│       ├── WindowResultFunction.java
│       ├── LatencyMetric.java
│       └── InfluxDBSink.java        # Local dev sink (replace with LambdaSink for AWS)
│
├── cloudwatch-exporter/             # Plain Kafka consumer → CloudWatch raw metrics
│   ├── build.gradle
│   └── src/main/java/com/monitor/cloudwatch/
│       ├── CloudWatchExporter.java  # Main — poll loop + PutMetricData
│       ├── NetworkEvent.java        # Kafka JSON POJO (net-latency)
│       └── RetransmitEvent.java     # Kafka JSON POJO (net-retransmit)
│
└── infra/                           # CDK TypeScript — Flink + metrics stacks
    ├── cdk.json
    ├── package.json
    ├── tsconfig.json
    ├── bin/infra.ts                 # App entry point → RtmFlinkStack + RtmMetricsStack
    ├── lib/flink-stack.ts           # Managed Flink + Lambda + CloudWatch logs
    └── lib/metrics-stack.ts         # CloudWatch dashboard + Amazon Managed Grafana
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
# AWS credentials (~/.aws/credentials or env vars)
# agent-infra RtmMskStack must be deployed first (provides MSK broker endpoints)
```

### Deploy AWS infra (Flink + metrics)

```bash
# Build the Flink JAR first
./gradlew :flink-latency-job:shadowJar

cd infra
npm install
npx cdk deploy --all   # deploys RtmFlinkStack then RtmMetricsStack
```

Stacks deploy in order. After deploy:
- Start the Flink app: `aws kinesisanalyticsv2 start-application --application-name network-latency-job`
- Open the `GrafanaWorkspaceUrl` output and assign users in the AWS console

### Local dev (docker-compose)

```bash
# Java 11+ and Docker required
docker compose up -d   # starts InfluxDB + Grafana on localhost

KAFKA_BROKERS=localhost:9092 \
INFLUXDB_URL=http://localhost:8086 \
INFLUXDB_TOKEN=my-token \
INFLUXDB_ORG=my-org \
INFLUXDB_BUCKET=network_metrics \
./gradlew :flink-latency-job:run
```

### Run cloudwatch-exporter (against MSK)

```bash
# Use the BrokersIamPublic output from agent-infra cdk deploy
KAFKA_BROKERS=<MSK_SASL_IAM_PUBLIC_ENDPOINT> \
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
