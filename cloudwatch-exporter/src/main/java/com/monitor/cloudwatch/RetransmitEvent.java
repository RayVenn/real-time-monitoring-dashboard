package com.monitor.cloudwatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON event produced by the Rust pcap agent to the {@code net-retransmit} topic.
 *
 * <pre>
 * {
 *   "src_ip": "192.168.0.166",
 *   "src_port": 55495,
 *   "dst_ip": "140.82.112.26",
 *   "dst_port": 443,
 *   "rto_us": 200000,
 *   "retransmit_count": 1,
 *   "timestamp_ns": 1772952115698113000
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetransmitEvent {

    @JsonProperty("src_ip")            private String src_ip;
    @JsonProperty("src_port")          private int    src_port;
    @JsonProperty("dst_ip")            private String dst_ip;
    @JsonProperty("dst_port")          private int    dst_port;
    @JsonProperty("rto_us")            private long   rto_us;
    @JsonProperty("retransmit_count")  private long   retransmit_count;
    @JsonProperty("timestamp_ns")      private long   timestamp_ns;

    public RetransmitEvent() {}

    public String getSrc_ip()           { return src_ip; }
    public int    getSrc_port()         { return src_port; }
    public String getDst_ip()           { return dst_ip; }
    public int    getDst_port()         { return dst_port; }
    public long   getRto_us()           { return rto_us; }
    public long   getRetransmit_count() { return retransmit_count; }
    public long   getTimestamp_ns()     { return timestamp_ns; }
}
