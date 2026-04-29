package com.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Flink sink that invokes an AWS Lambda function for each {@link LatencyMetric}.
 *
 * The Lambda receives the metric as a JSON payload and forwards it to CloudWatch.
 * LambdaClient is transient (not Java-serializable) and is recreated after
 * Flink deserialises the task graph.
 */
public class LambdaSink extends RichSinkFunction<LatencyMetric> {

    private static final Logger LOG = LoggerFactory.getLogger(LambdaSink.class);

    private final String region;
    private final String functionName;

    private transient LambdaClient lambdaClient;
    private transient ObjectMapper  mapper;

    public LambdaSink(String region, String functionName) {
        this.region       = region;
        this.functionName = functionName;
    }

    @Override
    public void open(Configuration parameters) {
        lambdaClient = LambdaClient.builder()
                .region(Region.of(region))
                .build();
        mapper = new ObjectMapper();
        LOG.info("LambdaSink opened — function={} region={}", functionName, region);
    }

    @Override
    public void invoke(LatencyMetric metric, Context context) throws Exception {
        String payload = mapper.writeValueAsString(metric);

        InvokeResponse response = lambdaClient.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .build());

        if (response.functionError() != null) {
            LOG.error("Lambda invocation error function={} error={} payload={}",
                    functionName, response.functionError(), payload);
        } else {
            LOG.debug("Lambda invoked ok function={} status={}", functionName,
                    response.statusCode());
        }
    }

    @Override
    public void close() {
        if (lambdaClient != null) {
            lambdaClient.close();
        }
    }
}
