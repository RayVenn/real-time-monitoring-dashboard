package com.monitor.cloudwatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON event produced by the Rust pcap agent to the {@code net-latency} topic.
 *
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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkEvent {

    @JsonProperty("src_ip")       private String src_ip;
    @JsonProperty("src_port")     private int    src_port;
    @JsonProperty("dst_ip")       private String dst_ip;
    @JsonProperty("dst_port")     private int    dst_port;
    @JsonProperty("rtt_us")       private long   rtt_us;
    @JsonProperty("timestamp_ns") private long   timestamp_ns;

    public NetworkEvent() {}

    public String getSrc_ip()       { return src_ip; }
    public int    getSrc_port()     { return src_port; }
    public String getDst_ip()       { return dst_ip; }
    public int    getDst_port()     { return dst_port; }
    public long   getRtt_us()       { return rtt_us; }
    public long   getTimestamp_ns() { return timestamp_ns; }
}
