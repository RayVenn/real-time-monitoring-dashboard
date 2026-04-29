package com.monitor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POJO for a TCP network event emitted by the C++ pcap agent.
 *
 * JSON produced by the agent:
 * {
 *   "src_ip": "192.168.0.166",
 *   "src_port": 55495,
 *   "dst_ip": "140.82.112.26",
 *   "dst_port": 443,
 *   "payload_bytes": 1460,
 *   "rtt_us": 31279,
 *   "timestamp_ns": 1772952115698113000
 * }
 *
 * src_ip is always the local machine. rtt_us is always > 0.
 * payload_bytes is 0 for handshake RTT (SYN/SYN-ACK), > 0 for data RTT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkEvent {

    @JsonProperty("src_ip")      private String srcIp;
    @JsonProperty("src_port")    private int    srcPort;
    @JsonProperty("dst_ip")      private String dstIp;
    @JsonProperty("dst_port")    private int    dstPort;
    @JsonProperty("payload_bytes") private long payloadBytes;
    @JsonProperty("rtt_us")      private long   rttUs;
    @JsonProperty("timestamp_ns") private long  timestampNs;

    public NetworkEvent() {}

    public String getSrcIp()       { return srcIp; }
    public void   setSrcIp(String v) { srcIp = v; }

    public int  getSrcPort()     { return srcPort; }
    public void setSrcPort(int v) { srcPort = v; }

    public String getDstIp()       { return dstIp; }
    public void   setDstIp(String v) { dstIp = v; }

    public int  getDstPort()     { return dstPort; }
    public void setDstPort(int v) { dstPort = v; }

    public long getPayloadBytes()     { return payloadBytes; }
    public void setPayloadBytes(long v) { payloadBytes = v; }

    public long getRttUs()      { return rttUs; }
    public void setRttUs(long v) { rttUs = v; }

    public long getTimestampNs()      { return timestampNs; }
    public void setTimestampNs(long v) { timestampNs = v; }
}
