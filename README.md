# CS6650 Assignment 2: Message Distribution and the Real Leaderboard

## Project Structure

- `server-v2/`: HTTP ingestion, RabbitMQ publishing, and WebSocket leaderboard delivery.
- `consumer/`: Validation, deduplication, vertical aggregation, Top N standings, and leaderboard broadcasting.
- `monitoring/`: RabbitMQ monitoring script.
- `deployment/`: Deployment documentation.
- `client-part2/`: HTTP load generation, WebSocket subscriptions, and result analysis.
- `results/`: Experimental logs, CSV files, and screenshots.
- `DESIGN.md`: Architecture, threading model, and limitations.
- `AI_USAGE.md`: AI usage reflection.

## Architecture

```text
Mac Load Client
    |
    v
Application Load Balancer
    |
    v
Ingest Servers
    |
    v
RabbitMQ: skitrak.exchange
    |
    v
resort.1 ... resort.10
    |
    v
Consumer: Deduplication, Aggregation, and Top N
    |
    v
RabbitMQ: leaderboard.fanout
    |
    v
Each Ingest Server's Local WebSocket Subscribers
```

RabbitMQ and the Consumer run on separate EC2 instances.

Each ingest server reuses an AMQP connection and borrows channels
from a thread-safe pool. A valid swipe receives HTTP 201 only after
a broker publisher confirmation.

Each resort has one serial aggregation execution stream.
The Consumer deduplicates events by `eventId` and manually acknowledges
each delivery after successful processing and any required
leaderboard publication.

The fanout backplane bridges the connection-locality problem:
each ingest server receives leaderboard snapshots and forwards them
to its own WebSocket subscribers.

## Requirements and Build

Requirements:

- Java 11
- Maven
- Apache Tomcat 9
- RabbitMQ
- AWS EC2 and an Application Load Balancer
- Python 3 for queue monitoring

Each module containing a `pom.xml` is a separate Maven project.

In IntelliJ's Maven panel:

- `server-v2`: Run `package` to build the WAR.
- `consumer`: Run `package` and deploy `consumer-1.0-SNAPSHOT.jar`.
  Do not deploy the JAR whose name starts with `original-`.
- `client-part2`: Run `compile`.

Do not run the local `ConsumerMain` while the cloud Consumer is
consuming the same resort queues.

## Queue Topology

- Topic exchange: `skitrak.exchange`
- Routing keys: `resort.{resortID}`
- Resort queues: `resort.1` through `resort.10`
- Dead-letter exchange: `skitrak.dlx`
- Dead-letter queue: `skitrak.expired`
- Leaderboard fanout exchange: `leaderboard.fanout`

Resort queues have message TTL and queue-length limits.
Each ingest server maintains its own temporary backplane queue.

## Deployment

AWS region: `us-west-2`

Deployment components:

- One, two, or four ingest EC2 instances
- One RabbitMQ EC2 instance using Docker and a persistent volume
- One Consumer EC2 instance managed by systemd
- One Application Load Balancer

Ingest paths:

```text
/opt/tomcat9/bin/setenv.sh
/opt/tomcat9/webapps/ski-servlet-v2.war
```

Consumer paths:

```text
/opt/skitrak-consumer/consumer.jar
/opt/skitrak-consumer/run.sh
/etc/systemd/system/skitrak-consumer.service
```

Each ingest instance must have a distinct `SERVER_ID`.

The Consumer service starts on boot and is configured to restart
after failure. Tomcat was started manually during the experiments.

Public EC2 addresses can change after stopping and starting instances.
Verify current addresses before using SSH or deployment commands.

## Application Endpoints

Health:

```text
GET /ski-servlet-v2/health
```

The response includes `status`, `publishReady`, `backplaneReady`,
and `serverId`.

Swipe ingestion:

```text
POST /ski-servlet-v2/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
```

WebSocket subscription:

```text
/ski-servlet-v2/leaderboard/{resortID}?dayID={dayID}
```

ALB base URL used during testing:

```text
http://cs6650-a2-alb-549848062.us-west-2.elb.amazonaws.com/ski-servlet-v2
```

The endpoint is available only while the lab resources are running.

## Load Client Configuration

Run `IngestLoadClient` in IntelliJ using the `client-part2` module.

Set Program arguments to the ALB base URL.

Example VM options for 500,000 events:

```text
-Xmx2g -Dload.events=500000 -Dload.threads=64 -Dload.warmupThreads=32 -Dload.warmupPerThread=1000 -Dload.day=7
```

Example VM options for 1,000,000 events:

```text
-Xmx2g -Dload.events=1000000 -Dload.threads=64 -Dload.warmupThreads=32 -Dload.warmupPerThread=1000 -Dload.day=9
```

The 32,000 warmup events are included in the total event count.
`load.threads` controls the main-phase HTTP sender thread count.

Use a separate console-output file for every run and an unused,
valid day ID to isolate leaderboard state between experiments.

The client waits until all ten WebSocket subscriptions are established
before sending swipes. Retries of the same swipe reuse its `eventId`.

After HTTP sending completes, subscriptions remain open while the
client waits for manual confirmation. Verify:

1. The Consumer `sourceId` has not changed.
2. The unique processed-event counter increased by the expected amount.
3. Resort queues have drained.
4. The dead-letter queue has not grown.

Then press Return in the IntelliJ Run console to close subscriptions
and finish reporting.

## Monitoring

`monitoring/rabbitmq_monitor.py` polls the management HTTP API through
an SSH tunnel and writes queue metrics to CSV.

Collected fields include:

- Ready, unacknowledged, and total messages
- Consumer count
- Publish, delivery, and acknowledgement rates
- Publish and acknowledgement counters, when available

Unavailable statistics remain blank rather than being recorded as zero.
The sampling interval is five seconds and can miss brief peaks.

Consumer logs include:

```text
CONSUMER_METRICS sourceId=... uniqueSwipesProcessed=...
lagSamples=... lagMeanMs=... lagMinMs=... lagMaxMs=...
lagAtLeast100Ms=... lagNegativeSamples=...
```

Consumer metrics are cumulative within one JVM process.
Compare before-and-after values only when `sourceId` is unchanged.

System CPU, memory, and network metrics were collected using `sar`.
These describe the entire EC2 host, not an individual application process.

## Performance Results

The following runs used the ALB, 64 main-phase sender threads,
and the same 32-thread, 32,000-event warmup.

| Ingest Instances | Events | Throughput Including Warmup (events/s) | Main Throughput (events/s) | HTTP P95 (ms) |
|---|---:|---:|---:|---:|
| 1 | 500,000 | 1,394.04 | 1,476.95 | 59.821 |
| 2 | 500,000 | 1,381.32 | 1,460.07 | 61.271 |
| 4 | 500,000 | 1,328.87 | 1,399.84 | 67.428 |

All three runs completed with:

- 500,000 successful events
- Zero final failed events
- Zero HTTP retries
- A matching increase in the Consumer's unique-event counter
- Empty resort queues and an empty dead-letter queue at final verification

For the two-instance run, access logs matched by `X-Run-ID` showed:

- Ingest-1: 250,001 requests
- Ingest-2: 249,999 requests

This verifies balanced request distribution for that run.

At 64 sender threads, increasing the ingest instance count did not
improve throughput. These experiments do not independently identify
the limiting component.

### Four-Instance 1M Retest

The completed retest used four ingest instances and 64 main-phase
sender threads.

- Warmup successes: 32,000
- Main-phase successes: 968,000
- Final failed events: 0
- HTTP retries: 0
- Main-phase runtime: 679.477 seconds
- Main-phase throughput: 1,424.62 events/s
- Consumer unique-event counter: 0 to 1,000,000
- Final resort and dead-letter queues: empty
- Observed mean Consumer lag: 5.460 ms
- Observed maximum Consumer lag: 344.881 ms
- Lag samples at or above 100 ms: 1,682 of 1,000,000
- Negative Consumer lag samples: 0

Evidence:

```text
results/96-four1m-retest-consumer-before.log
results/97-four1m-retest-queues.csv
results/98-four1m-retest-client-console.log
results/100-four1m-retest-consumer-after.log
results/103-four1m-retest-*-system.log
```

An earlier 1M attempt was interrupted by an infrastructure stop/start
and is retained separately. It is not counted as a completed successful run.

Matching event counts and drained queues support that no loss was
observed in these completed runs. They do not prove loss-free behavior
under every failure or load condition.

## Measurement Definitions

### HTTP Latency

HTTP request durations use the client's monotonic clock.
They do not require synchronization between the Mac and EC2 clocks.

### Consumer Lag

Consumer lag is the difference between the ingest event's `timestamp`
and the time immediately before Consumer aggregation.

It includes publication, transport, broker waiting, and Consumer-local
waiting. It is not a direct measurement of broker queue residence time.

Lag samples are recorded per valid delivery, so redeliveries can make
the sample count differ from the unique-event count.

This cross-host metric depends on clock synchronization.
Zero negative samples alone does not establish exact timing accuracy.

### Push Latency

Push latency is the difference between the Consumer's leaderboard
publication timestamp and the client's complete-message receive time.

The first snapshot on each connection and duplicate or older versions
from the same source are excluded from latency sampling.
Excluding the first snapshot can also discard a valid live update.

Negative push-latency samples occurred during formal tests.
Raw values were retained, and affected summaries were marked invalid.
They are not reported as valid one-way latency or Consumer lag.

### Throughput

Use ingestion throughput or main-phase throughput for comparisons.
Total wall-clock time includes manual verification waits and therefore
does not provide a comparable sending-throughput measurement.

## Limitations and Incomplete Work

- Aggregation and deduplication state are held in memory and reset
  when the Consumer process restarts.
- Each resort has one serial aggregation execution stream.
  Effective resort aggregation concurrency is capped at ten.
- Dynamic Consumer scaling has not been implemented.
- The full Assignment 2 client-thread matrix of 64/128/256/512
  and Consumer-thread matrix of 10/20/40/80 has not been completed.
- Assignment 1 thread experiments are not used as Assignment 2 results.
- The maximum-lag observations exceed the 100 ms target in some cases.
- Push-latency measurement remains limited by cross-machine clock error.
- Five-second monitoring samples may miss transient queue-depth peaks.
- ALB request distribution was verified using backend access logs;
  this is distinct from CloudWatch ALB metric evidence.

## Credentials

Do not commit SSH private keys, passwords, password files,
access tokens, or configuration files containing real credentials.

Credentials are supplied through environment configuration or
protected server-side files.