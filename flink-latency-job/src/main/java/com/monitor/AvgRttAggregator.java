package com.monitor;

import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * Incrementally aggregates RTT values within a tumbling window.
 *
 * <p>Accumulator layout: {@code long[2]}
 * <ul>
 *   <li>Index 0: running sum of {@code rtt_us} values</li>
 *   <li>Index 1: count of events seen</li>
 * </ul>
 *
 * <p>Using {@code AggregateFunction} (rather than a {@code ReduceFunction} or
 * full {@code ProcessWindowFunction}) keeps per-window state minimal — Flink
 * only needs to store two longs regardless of window size.
 */
public class AvgRttAggregator implements AggregateFunction<NetworkEvent, long[], Double> {

    /**
     * Creates a fresh accumulator with sum=0 and count=0.
     */
    @Override
    public long[] createAccumulator() {
        return new long[]{0L, 0L};
    }

    /**
     * Adds one {@link NetworkEvent} to the accumulator.
     *
     * @param event       the incoming network event
     * @param accumulator [sum_rtt_us, count]
     * @return updated accumulator
     */
    @Override
    public long[] add(NetworkEvent event, long[] accumulator) {
        accumulator[0] += event.getRtt_us(); // sum_rtt_us
        accumulator[1] += 1L;               // count
        return accumulator;
    }

    /**
     * Produces the final average RTT in microseconds.
     *
     * <p>Returns 0.0 if no events were seen (guards against division by zero
     * in edge cases where a window fires with an empty accumulator).
     *
     * @param accumulator [sum_rtt_us, count]
     * @return average RTT in microseconds, or 0.0 if count == 0
     */
    @Override
    public Double getResult(long[] accumulator) {
        if (accumulator[1] == 0L) {
            return 0.0;
        }
        return (double) accumulator[0] / (double) accumulator[1];
    }

    /**
     * Merges two accumulators (required for session windows and parallel
     * pre-aggregation; also called during recovery).
     *
     * @param a first accumulator
     * @param b second accumulator
     * @return merged accumulator (modifies {@code a} in place)
     */
    @Override
    public long[] merge(long[] a, long[] b) {
        a[0] += b[0]; // merge sums
        a[1] += b[1]; // merge counts
        return a;
    }
}
