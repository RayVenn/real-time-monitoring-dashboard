#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { FlinkStack } from '../lib/flink-stack';
import { MetricsStack } from '../lib/metrics-stack';

const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region:  process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

// Deploy order: FlinkStack first (reads MSK SSM params written by agent-infra),
// then MetricsStack (CloudWatch dashboard + Managed Grafana workspace).
new FlinkStack(app, 'RtmFlinkStack', {
  env,
  description: 'Real-time monitoring: Managed Flink job + Lambda → CloudWatch',
});

new MetricsStack(app, 'RtmMetricsStack', {
  env,
  description: 'Real-time monitoring: CloudWatch dashboard + Amazon Managed Grafana',
});
