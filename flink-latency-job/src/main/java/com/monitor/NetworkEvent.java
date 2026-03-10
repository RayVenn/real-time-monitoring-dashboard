package com.monitor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POJO representing a single TCP network event emitted by the Rust pcap agent.
 *
 * <p>JSON format produced by the agent:
 * <pre>
 * {
 *   "src_ip": "192.168.0.166",
 *   "src_port": 55495,
 *   "dst_ip": "140.82.112.26",
 *   "dst_port": 443,
 *   "rtt_us": 31279,
 *   "timestamp_ns": 1772952115698113000
 * }
 * </pre>
 *
 * <p>{@code rtt_us} is always > 0 (events with no RTT are dropped by the agent).
 * {@code src_ip} is always the local machine. {@code timestamp_ns} is the Unix
 * epoch time in nanoseconds at which the RTT was measured.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkEvent {

    @JsonProperty("src_ip")
    private String src_ip;

    @JsonProperty("src_port")
    private int src_port;

    @JsonProperty("dst_ip")
    private String dst_ip;

    @JsonProperty("dst_port")
    private int dst_port;

    /** Round-trip time in microseconds. Always > 0. */
    @JsonProperty("rtt_us")
    private long rtt_us;

    /** Event timestamp in nanoseconds since Unix epoch. */
    @JsonProperty("timestamp_ns")
    private long timestamp_ns;

    /** Required by Flink's POJO serializer and Jackson. */
    public NetworkEvent() {}

    public NetworkEvent(String src_ip, int src_port, String dst_ip, int dst_port,
                        long rtt_us, long timestamp_ns) {
        this.src_ip = src_ip;
        this.src_port = src_port;
        this.dst_ip = dst_ip;
        this.dst_port = dst_port;
        this.rtt_us = rtt_us;
        this.timestamp_ns = timestamp_ns;
    }

    public String getSrc_ip() {
        return src_ip;
    }

    public void setSrc_ip(String src_ip) {
        this.src_ip = src_ip;
    }

    public int getSrc_port() {
        return src_port;
    }

    public void setSrc_port(int src_port) {
        this.src_port = src_port;
    }

    public String getDst_ip() {
        return dst_ip;
    }

    public void setDst_ip(String dst_ip) {
        this.dst_ip = dst_ip;
    }

    public int getDst_port() {
        return dst_port;
    }

    public void setDst_port(int dst_port) {
        this.dst_port = dst_port;
    }

    public long getRtt_us() {
        return rtt_us;
    }

    public void setRtt_us(long rtt_us) {
        this.rtt_us = rtt_us;
    }

    public long getTimestamp_ns() {
        return timestamp_ns;
    }

    public void setTimestamp_ns(long timestamp_ns) {
        this.timestamp_ns = timestamp_ns;
    }

    @Override
    public String toString() {
        return "NetworkEvent{" +
                "src_ip='" + src_ip + '\'' +
                ", src_port=" + src_port +
                ", dst_ip='" + dst_ip + '\'' +
                ", dst_port=" + dst_port +
                ", rtt_us=" + rtt_us +
                ", timestamp_ns=" + timestamp_ns +
                '}';
    }
}
