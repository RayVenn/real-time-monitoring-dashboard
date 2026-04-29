import boto3
import json
import logging
import os
from datetime import datetime, timezone

logger = logging.getLogger()
logger.setLevel(logging.INFO)

cloudwatch = boto3.client(
    "cloudwatch",
    region_name=os.environ.get("AWS_REGION", "us-east-1"),
)

NAMESPACE = "Network/Latency"


def handler(event, context):
    """
    Receives a LatencyMetric JSON from the Flink LambdaSink and publishes it
    to CloudWatch as a custom metric.

    Expected payload:
    {
        "dstIp":       "140.82.112.26",
        "avgRttUs":    31279.0,
        "windowEndMs": 1772952115000
    }

    CloudWatch metric published:
      Namespace:  Network/Latency
      MetricName: AvgRttUs
      Dimension:  DstIp = <dstIp>
      Value:      <avgRttUs>
      Unit:       Microseconds
    """
    logger.info("Received event: %s", json.dumps(event))

    dst_ip      = event["dstIp"]
    avg_rtt_us  = float(event["avgRttUs"])
    window_end  = datetime.fromtimestamp(
        event["windowEndMs"] / 1000.0, tz=timezone.utc
    )

    cloudwatch.put_metric_data(
        Namespace=NAMESPACE,
        MetricData=[
            {
                "MetricName": "AvgRttUs",
                "Dimensions": [{"Name": "DstIp", "Value": dst_ip}],
                "Timestamp":  window_end,
                "Value":      avg_rtt_us,
                "Unit":       "Microseconds",
            }
        ],
    )

    logger.info(
        "Published AvgRttUs=%.1f us for DstIp=%s at %s",
        avg_rtt_us, dst_ip, window_end.isoformat(),
    )

    return {"statusCode": 200}
