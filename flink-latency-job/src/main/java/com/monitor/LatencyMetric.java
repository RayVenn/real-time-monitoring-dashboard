package com.monitor;

/**
 * Aggregated latency metric for a single destination IP over a tumbling window.
 *
 * <p>Produced by {@link WindowResultFunction} after aggregating all
 * {@link NetworkEvent}s in a 1-minute tumbling event-time window.
 */
public class LatencyMetric {

    /** Destination IP address (the remote host being measured). */
    private String dst_ip;

    /** Average round-trip time in microseconds for this window. */
    private double avg_rtt_us;

    /** Unix epoch timestamp in milliseconds of the window's end boundary. */
    private long window_end_ms;

    /** Required by Flink's POJO serializer. */
    public LatencyMetric() {}

    public LatencyMetric(String dst_ip, double avg_rtt_us, long window_end_ms) {
        this.dst_ip = dst_ip;
        this.avg_rtt_us = avg_rtt_us;
        this.window_end_ms = window_end_ms;
    }

    public String getDst_ip() {
        return dst_ip;
    }

    public void setDst_ip(String dst_ip) {
        this.dst_ip = dst_ip;
    }

    public double getAvg_rtt_us() {
        return avg_rtt_us;
    }

    public void setAvg_rtt_us(double avg_rtt_us) {
        this.avg_rtt_us = avg_rtt_us;
    }

    public long getWindow_end_ms() {
        return window_end_ms;
    }

    public void setWindow_end_ms(long window_end_ms) {
        this.window_end_ms = window_end_ms;
    }

    @Override
    public String toString() {
        return "LatencyMetric{" +
                "dst_ip='" + dst_ip + '\'' +
                ", avg_rtt_us=" + avg_rtt_us +
                ", window_end_ms=" + window_end_ms +
                '}';
    }
}
