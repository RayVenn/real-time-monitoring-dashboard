# Flink Latency Job

A Flink streaming job that consumes TCP network events from Kafka, computes **1-minute average RTT per destination IP**, and writes results to InfluxDB for Grafana dashboards.

Part of the [real-time-monitoring](../) workspace alongside the Rust pcap agent.

## Pipeline

```
Rust pcap agent
  → Kafka (net-latency)
    → Flink (1-min tumbling window, avg RTT per dst_ip)
      → InfluxDB
        → Grafana
```

## Quick Start

### 1. Start infrastructure

```bash
docker compose up -d
```

This starts InfluxDB (`:8086`) and Grafana (`:3000`).

### 2. Build

```bash
./gradlew shadowJar
```

### 3. Run

```bash
KAFKA_BROKERS=localhost:9092 \
INFLUXDB_URL=http://localhost:8086 \
INFLUXDB_TOKEN=my-token \
INFLUXDB_ORG=my-org \
INFLUXDB_BUCKET=network_metrics \
java -jar build/libs/flink-latency-job-1.0-SNAPSHOT.jar
```

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |
| `INFLUXDB_URL` | `http://localhost:8086` | InfluxDB URL |
| `INFLUXDB_TOKEN` | — | InfluxDB API token |
| `INFLUXDB_ORG` | — | InfluxDB organisation |
| `INFLUXDB_BUCKET` | — | InfluxDB bucket |

## Grafana Dashboard

1. Open http://localhost:3000 → `admin / admin`
2. Add InfluxDB data source (Flux, `http://influxdb:8086`, token `my-token`, org `my-org`)
3. Create a time series panel:

```flux
from(bucket: "network_metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "avg_latency" and r._field == "avg_rtt_us")
  |> group(columns: ["dst_ip"])
```

## Infrastructure credentials (local dev only)

| Service | URL | Credentials |
|---|---|---|
| InfluxDB | http://localhost:8086 | admin / password123 |
| Grafana | http://localhost:3000 | admin / admin |
