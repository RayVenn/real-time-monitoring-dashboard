import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as grafana from 'aws-cdk-lib/aws-grafana';
import * as iam from 'aws-cdk-lib/aws-iam';

/**
 * MetricsStack provisions the visualization tier:
 *
 *   CloudWatch (namespace: Network/Latency)
 *     ├── CloudWatch Dashboard  — built-in, no extra cost
 *     └── Amazon Managed Grafana workspace
 *           └── CloudWatch data source (IAM auth, read-only)
 *
 * AMG prerequisite: AWS IAM Identity Center (SSO) must be enabled in the account.
 * After deploy, assign users to the workspace in the AWS console.
 */
export class MetricsStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // ── CloudWatch Dashboard ──────────────────────────────────────────────────
    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: 'NetworkLatency',
    });

    const latency = (stat: string, label: string) =>
      new cloudwatch.Metric({
        namespace: 'Network/Latency',
        metricName: 'AvgRttUs',
        statistic: stat,
        label,
        period: cdk.Duration.minutes(1),
        dimensionsMap: {},
      });

    const retransmit = new cloudwatch.Metric({
      namespace: 'Network Latency',   // written by cloudwatch-exporter Java module
      metricName: 'RetransmitCount',
      statistic: 'Sum',
      label: 'retransmits / min',
      period: cdk.Duration.minutes(1),
    });

    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'Avg RTT per dst_ip (μs)  — 1-min Flink window',
        width: 12,
        left: [
          latency('Average', 'avg'),
          latency('p90', 'p90'),
          latency('p99', 'p99'),
        ],
      }),
      new cloudwatch.GraphWidget({
        title: 'Retransmissions / min',
        width: 12,
        left: [retransmit],
      }),
      new cloudwatch.SingleValueWidget({
        title: 'Current Avg RTT (μs)',
        width: 6,
        metrics: [latency('Average', 'avg')],
      }),
      new cloudwatch.AlarmStatusWidget({
        title: 'Alarms',
        width: 6,
        alarms: [],
      }),
    );

    // ── Amazon Managed Grafana workspace ─────────────────────────────────────
    // AMG service principal reads CloudWatch metrics on your behalf.
    const grafanaRole = new iam.Role(this, 'GrafanaRole', {
      assumedBy: new iam.ServicePrincipal('grafana.amazonaws.com'),
      description: 'Allows AMG workspace to query CloudWatch',
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('CloudWatchReadOnlyAccess'),
      ],
    });

    // AWS_SSO auth requires IAM Identity Center to be enabled in the account.
    // Switch to SAML if you prefer a third-party IdP.
    const workspace = new grafana.CfnWorkspace(this, 'GrafanaWorkspace', {
      name: 'rtm-grafana',
      description: 'Real-time network latency monitoring',
      accountAccessType: 'CURRENT_ACCOUNT',
      authenticationProviders: ['AWS_SSO'],
      permissionType: 'SERVICE_MANAGED',
      roleArn: grafanaRole.roleArn,
      dataSources: ['CLOUDWATCH'],
    });

    // ── Outputs ───────────────────────────────────────────────────────────────
    new cdk.CfnOutput(this, 'GrafanaWorkspaceUrl', {
      value: cdk.Fn.join('', ['https://', workspace.attrEndpoint]),
      description: 'Amazon Managed Grafana — add CloudWatch data source and import dashboards',
    });

    new cdk.CfnOutput(this, 'CloudWatchDashboardUrl', {
      value: cdk.Fn.join('', [
        'https://', this.region,
        '.console.aws.amazon.com/cloudwatch/home#dashboards:name=NetworkLatency',
      ]),
    });
  }
}
