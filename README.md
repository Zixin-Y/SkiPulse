# CS6650 Assignment 1: Ski Event Load Testing

## Project Structure

- `server/`: Java servlet and WebSocket server.
- `client-part1/`: Initial concurrent load client.
- `client-part2/`: Load client with CSV recording, latency analysis,
  throughput buckets, and WebSocket subscription metrics.
- `results/`: Test logs, CSV files, screenshots, charts, and analysis notes.

## Requirements

- Java 11
- Maven
- Apache Tomcat 9
- An EC2 instance reachable from the client
- Network access to port 8080
- SSH access for deployment

The load client runs on the local Mac.
The server runs on EC2.

## Build

Each directory containing a `pom.xml` is a separate Maven project.

In IntelliJ, open the relevant Maven project and use
"Execute Maven Goal".

For the server, enter:

```text
clean compile war:war
```

For either client, enter:

```text
compile
```

When using IntelliJ's Maven goal field, do not include the `mvn` prefix.

## Server Deployment

The server WAR is built under `server/target/`.

Copy the WAR from the local computer to EC2:

```bash
scp -i ~/.ssh/cs6650-lab1-key.pem \
  server/target/ski-servlet-1.0-SNAPSHOT.war \
  ec2-user@EC2_PUBLIC_IP:/tmp/
```

This command assumes the terminal is in the repository root.
Replace the key path and EC2_PUBLIC_IP as appropriate.

On EC2, stop Tomcat before replacing the deployed WAR:

```bash
/opt/tomcat9/bin/shutdown.sh
```

After Tomcat has stopped, copy the WAR:

```bash
sudo cp /tmp/ski-servlet-1.0-SNAPSHOT.war \
  /opt/tomcat9/webapps/ski-servlet.war
```

Start Tomcat:

```bash
/opt/tomcat9/bin/startup.sh
```

Verify the server on EC2:

```bash
curl -i http://localhost:8080/ski-servlet/health
```

Expected response: HTTP 200 with `{"status":"UP"}`.

## Server Endpoints

### Health

```text
GET /ski-servlet/health
```

### Ski Event Ingestion

```text
POST /ski-servlet/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
```

Example JSON body:

```json
{"time":120,"liftID":10}
```

Valid events receive HTTP 201.
Invalid requests receive HTTP 400.

### Leaderboard Subscription

```text
ws://EC2_PUBLIC_IP:8080/ski-servlet/leaderboard/{resortID}
```

The server sends synthetic leaderboard updates approximately
every 500 milliseconds. These updates are not computed from
the submitted ski events.

## Running the Load Clients

Open `client-part1` or `client-part2` as a Maven project in IntelliJ.

Run the main method in `IngestLoadClient`.

Set its Program arguments to:

```text
http://EC2_PUBLIC_IP:8080/ski-servlet
```

Always supply the current EC2 address rather than relying on
a hard-coded default address.

The formal workload contains 500,000 events:

- Warmup: 32 sender threads, 32,000 events.
- Main phase: remaining 468,000 events.
- Main sender thread counts tested: 64, 128, and 256.
- Ten persistent WebSocket subscriptions run alongside ingestion.

The event generator prepares events before the sender phases.
Sender threads share an HTTP client to allow connection reuse.
Failed requests are retried up to five times with exponential backoff.

## Part 2 Output

Part 2 writes an HTTP attempt CSV with these columns:

```text
timestamp,requestType,latency,statusCode,resortID
```

A status code of zero indicates a client-side network error.

It also writes:

- A push-latency CSV.
- A throughput CSV using 10-second buckets.
- Console summaries of ingestion and subscription metrics.

The final partial throughput bucket uses its actual duration.

To preserve console output in IntelliJ:

1. Open the run configuration.
2. Select "Modify options".
3. Enable "Save console output to file".
4. Choose a new log filename in `results/` for each run.

## Performance Results

All runs listed below completed 500,000 successful events
with zero final failed events.

| Main threads | Run | Main throughput (events/s) |
|---|---|---:|
| 64 | Initial formal run | 1495.52 |
| 128 | Formal run | 2691.50 |
| 256 | Run 1 | 4632.00 |
| 256 | Run 2 | 4655.29 |
| 256 | Connection observation run | 4340.52 |

Main throughput excludes warmup.
Overall and ingestion throughput use different timing boundaries
and are reported separately in the logs.

The sequential HTTP baseline mean was 37.171 ms.
Assuming constant response time and no other bottlenecks,
256 concurrent requests predict approximately 6887 events/s.

These predictions are estimates. Network conditions, retries,
server resources, and client overhead can affect measured results.

## WebSocket Verification

Run `SubscriptionPoolCheck` with Program arguments:

```text
ws://EC2_PUBLIC_IP:8080/ski-servlet
```

This checks ten subscriptions for approximately 15 seconds.

The clock-check run received 310 updates in total,
with one connection and zero reconnections per resort.
It was a subscription-only test, separate from the HTTP load tests.

## HTTP Connection Reuse Observation

The connection observation run attached an `X-Run-ID` header
to each HTTP request.

Tomcat access logging was configured with:

```xml
<Valve className="org.apache.catalina.valves.AccessLogValve"
       directory="logs"
       prefix="assignment1_access_log"
       suffix=".txt"
       pattern="%a %{remote}p %{begin:msec}t &quot;%r&quot; %s %b %D %X %{X-Run-ID}i" />
```

Changing this configuration requires a Tomcat restart.

For the observed run:

- Server logged HTTP requests: 500,417.
- Distinct client IP:port pairs: 2,843.
- Pairs handling multiple requests: 2,843.
- Average requests per pair: 176.02.

These observations support HTTP connection reuse.
Distinct IP:port pairs are not an exact TCP connection count,
because ports may be reused across connections.
HTTP retries are not equivalent to reconnections.

## Measurement Limitations

HTTP request durations use a monotonic clock on the client.

Push latency is calculated from the difference between the
server message timestamp and the client receive timestamp.
It therefore depends on clock alignment between the Mac and EC2.

Negative push-latency samples occurred, including after a manual
Mac time synchronization. Raw samples were retained without
taking absolute values or removing negative measurements.

Push timestamp differences are reported with this limitation
and should not be interpreted as precise absolute one-way latency.
Positive values alone do not establish accurate clock alignment.

Results from different runs are kept separate.
A subscription-only test is not a substitute for a load-test result.

## Result Files

See `results/` for:

- Part 1 baseline output and screenshots.
- Part 2 performance logs and CSV files.
- Throughput charts.
- `performance-comparison.txt`.
- `part2-256threads-http-connection-summary.txt`.
- `part2-ws-clock-check.txt`.

## Security

Private SSH keys and credentials must not be committed
to the repository.