import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as assets from 'aws-cdk-lib/aws-s3-assets';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import * as kfv2 from 'aws-cdk-lib/aws-kinesisanalyticsv2';

/**
 * FlinkStack provisions the streaming compute tier:
 *
 *   MSK net-latency topic
 *     → Amazon Managed Flink (1-min tumbling window, avg RTT per dst_ip)
 *       → Lambda rtt-to-cloudwatch
 *         → CloudWatch  Network/Latency / AvgRttUs
 *
 * Prerequisites:
 *   1. agent-infra RtmMskStack must be deployed first (populates SSM params).
 *   2. Build the Flink JAR:  cd flink-latency-job && ./gradlew shadowJar
 */
export class FlinkStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // ── Read MSK coordinates from SSM (written by agent-infra MskStack) ──────
    // Private SASL/IAM bootstrap — Flink runs inside the same default VPC.
    const mskBrokers = ssm.StringParameter.valueForStringParameter(
      this, '/rtm/msk/brokers-iam',
    );
    // Cluster ARN used to scope IAM policy to this specific cluster.
    const mskClusterArn = ssm.StringParameter.valueForStringParameter(
      this, '/rtm/msk/cluster-arn',
    );

    // ── VPC — same default VPC used by agent-infra ────────────────────────────
    const vpc = ec2.Vpc.fromLookup(this, 'DefaultVpc', { isDefault: true });

    const flinkSg = new ec2.SecurityGroup(this, 'FlinkSg', {
      vpc,
      description: 'Managed Flink — outbound to MSK port 9098',
    });

    // ── Lambda: Flink → CloudWatch ────────────────────────────────────────────
    const rttLambda = new lambda.Function(this, 'RttToCloudWatch', {
      functionName: 'rtt-to-cloudwatch',
      runtime: lambda.Runtime.PYTHON_3_12,
      handler: 'handler.handler',
      code: lambda.Code.fromAsset(
        path.join(__dirname, '../../lambda'),
      ),
      timeout: cdk.Duration.seconds(30),
      description: 'Receives windowed avg RTT from Flink and publishes to CloudWatch',
    });

    rttLambda.addToRolePolicy(new iam.PolicyStatement({
      actions: ['cloudwatch:PutMetricData'],
      resources: ['*'],
    }));

    // ── Flink JAR — built from flink-latency-job Gradle submodule ────────────
    const flinkJar = new assets.Asset(this, 'FlinkJar', {
      path: path.join(
        __dirname,
        '../../flink-latency-job/build/libs/flink-latency-job.jar',
      ),
    });

    // ── IAM execution role for Managed Flink ─────────────────────────────────
    const flinkRole = new iam.Role(this, 'FlinkRole', {
      assumedBy: new iam.ServicePrincipal('kinesisanalytics.amazonaws.com'),
      description: 'Execution role for the network-latency Flink job',
    });

    rttLambda.grantInvoke(flinkRole);
    flinkJar.grantRead(flinkRole);

    // MSK IAM auth — scoped to the cluster ARN from SSM.
    // Topic/group ARNs use a wildcard for the cluster UUID (known only after deploy).
    flinkRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'kafka-cluster:Connect',
        'kafka-cluster:DescribeCluster',
      ],
      resources: [mskClusterArn],
    }));

    flinkRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'kafka-cluster:ReadData',
        'kafka-cluster:DescribeTopic',
      ],
      resources: [
        `arn:aws:kafka:${this.region}:${this.account}:topic/rtm-cluster/*/net-latency`,
        `arn:aws:kafka:${this.region}:${this.account}:topic/rtm-cluster/*/net-retransmit`,
      ],
    }));

    flinkRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'kafka-cluster:AlterGroup',
        'kafka-cluster:DescribeGroup',
      ],
      resources: [
        `arn:aws:kafka:${this.region}:${this.account}:group/rtm-cluster/*/flink-*`,
      ],
    }));

    flinkRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'ec2:DescribeVpcs', 'ec2:DescribeSubnets', 'ec2:DescribeSecurityGroups',
        'ec2:DescribeNetworkInterfaces', 'ec2:CreateNetworkInterface',
        'ec2:CreateNetworkInterfacePermission', 'ec2:DeleteNetworkInterface',
      ],
      resources: ['*'],
    }));

    flinkRole.addToPolicy(new iam.PolicyStatement({
      actions: [
        'logs:CreateLogGroup', 'logs:CreateLogDelivery', 'logs:PutLogEvents',
        'logs:DescribeLogGroups', 'logs:DescribeLogStreams',
      ],
      resources: ['*'],
    }));

    // ── CloudWatch log group for Flink ────────────────────────────────────────
    const logGroup = new logs.LogGroup(this, 'FlinkLogGroup', {
      logGroupName: '/aws/managed-flink/network-latency-job',
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const logStream = new logs.LogStream(this, 'FlinkLogStream', {
      logGroup,
      logStreamName: 'flink-job',
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // ── Amazon Managed Flink application ──────────────────────────────────────
    const flinkApp = new kfv2.CfnApplication(this, 'LatencyFlinkApp', {
      applicationName: 'network-latency-job',
      runtimeEnvironment: 'FLINK-1_18',
      serviceExecutionRole: flinkRole.roleArn,

      applicationConfiguration: {
        applicationCodeConfiguration: {
          codeContent: {
            s3ContentLocation: {
              bucketArn: flinkJar.bucket.bucketArn,
              fileKey: flinkJar.s3ObjectKey,
            },
          },
          codeContentType: 'ZIPFILE',
        },

        environmentProperties: {
          propertyGroups: [{
            propertyGroupId: 'FlinkApplicationProperties',
            propertyMap: {
              KAFKA_BROKERS:   mskBrokers,
              AWS_REGION:      this.region,
              LAMBDA_FUNCTION: rttLambda.functionName,
            },
          }],
        },

        flinkApplicationConfiguration: {
          parallelismConfiguration: {
            configurationType: 'CUSTOM',
            parallelism: 1,
            parallelismPerKpu: 1,
            autoScalingEnabled: false,
          },
          checkpointConfiguration: {
            configurationType: 'DEFAULT',
          },
          monitoringConfiguration: {
            configurationType: 'CUSTOM',
            logLevel: 'INFO',
            metricsLevel: 'APPLICATION',
          },
        },

        vpcConfigurations: [{
          subnetIds: [vpc.publicSubnets[0].subnetId],
          securityGroupIds: [flinkSg.securityGroupId],
        }],

        applicationSnapshotConfiguration: {
          snapshotsEnabled: false,
        },
      },
    });

    const flinkLogging = new kfv2.CfnApplicationCloudWatchLoggingOption(this, 'FlinkLogging', {
      applicationName: 'network-latency-job',
      cloudWatchLoggingOption: {
        logStreamArn: cdk.Fn.join(':', [
          'arn:aws:logs',
          this.region,
          this.account,
          'log-group', logGroup.logGroupName,
          'log-stream', logStream.logStreamName,
        ]),
      },
    });

    // Logging option references the app by name (not a CFN ref), so declare
    // the dependency explicitly so CloudFormation creates the app first.
    flinkLogging.addDependency(flinkApp);

    // ── Outputs ───────────────────────────────────────────────────────────────
    new cdk.CfnOutput(this, 'LambdaFunctionName', {
      value: rttLambda.functionName,
    });
    new cdk.CfnOutput(this, 'FlinkAppName', {
      value: 'network-latency-job',
      description: 'Start via: aws kinesisanalyticsv2 start-application --application-name network-latency-job',
    });
  }
}
