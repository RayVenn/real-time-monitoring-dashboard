# Real-Time Monitoring Dashboard

Downstream consumers of the [Rust pcap agent](../real-time-monitoring-agent) Kafka topics.

## Pipeline

```
Rust pcap agent
  → Kafka
      ├── net-latency
      └── net-retransmit
            │
            ├── flink-latency-job    → InfluxDB → Grafana
            └── cloudwatch-exporter  → AWS CloudWatch
```

## Modules

| Module | Description |
|---|---|
| `flink-latency-job` | 1-min avg RTT per dst_ip → InfluxDB → Grafana |
| `cloudwatch-exporter` | Raw RTT + retransmit events → CloudWatch metrics |

## Quick Start

### 1. Start infrastructure

```bash
docker compose up -d
```

Starts InfluxDB (`:8086`) and Grafana (`:3000`).

### 2. Run Flink job

```bash
KAFKA_BROKERS=localhost:9092 \
INFLUXDB_URL=http://localhost:8086 \
INFLUXDB_TOKEN=my-token \
INFLUXDB_ORG=my-org \
INFLUXDB_BUCKET=network_metrics \
./gradlew :flink-latency-job:run
```

### 3. Run CloudWatch exporter

```bash
KAFKA_BROKERS=localhost:9092 \
AWS_REGION=us-east-1 \
./gradlew :cloudwatch-exporter:run
```

AWS credentials are picked up from `~/.aws/credentials` or environment variables.

## Infrastructure Credentials (local dev only)

| Service | URL | Credentials |
|---|---|---|
| InfluxDB | http://localhost:8086 | admin / password123 |
| Grafana | http://localhost:3000 | admin / admin |
