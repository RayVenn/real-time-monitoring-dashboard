package com.monitor;

import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * Wraps the pre-aggregated average RTT (produced by {@link AvgRttAggregator})
 * into a {@link LatencyMetric}, enriching it with the window's end timestamp.
 *
 * <p>This class is used as the second argument to
 * {@code WindowedStream.aggregate(AggregateFunction, ProcessWindowFunction)}.
 * Flink calls {@link AvgRttAggregator} incrementally as events arrive, then
 * calls this function once per window with the single aggregated result — so
 * the {@code averages} iterable always contains exactly one element.
 *
 * <p>Type parameters:
 * <ul>
 *   <li>{@code Double} — input from {@link AvgRttAggregator#getResult}</li>
 *   <li>{@code LatencyMetric} — output emitted downstream</li>
 *   <li>{@code String} — key type (dst_ip)</li>
 *   <li>{@code TimeWindow} — window type</li>
 * </ul>
 */
public class WindowResultFunction
        extends ProcessWindowFunction<Double, LatencyMetric, String, TimeWindow> {

    /**
     * Called once per window when the window fires.
     *
     * @param dstIp     the keyed destination IP address
     * @param context   provides window metadata (start/end times)
     * @param averages  iterable containing exactly one pre-aggregated average
     * @param out       collector to emit the result downstream
     */
    @Override
    public void process(String dstIp,
                        Context context,
                        Iterable<Double> averages,
                        Collector<LatencyMetric> out) {

        // averages contains exactly one element because AvgRttAggregator
        // reduces all events in the window to a single Double.
        double avgRtt = averages.iterator().next();

        // window().getEnd() returns the exclusive upper bound of the window
        // in milliseconds since Unix epoch (event time).
        long windowEndMs = context.window().getEnd();

        out.collect(new LatencyMetric(dstIp, avgRtt, windowEndMs));
    }
}
