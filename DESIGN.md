# Assignment 1 Design

## Architecture

The Java 11 load client runs on a local Mac and communicates
with a Tomcat 9 server deployed on EC2.

HTTP flow:
SwipeGenerator → BlockingQueue → sender threads →
shared HttpClient → EC2 SkierServlet.

WebSocket flow:
EC2 LeaderboardEndpoint → ten persistent
LeaderboardSubscriber instances → client metrics.

SkierServlet validates request paths and JSON bodies.
Valid ski events receive HTTP 201; invalid requests receive
HTTP 400. HealthServlet provides a health-check endpoint.

LeaderboardEndpoint publishes synthetic leaderboard messages
approximately every 500 ms. These messages are independent
of the submitted events. No database or event aggregation
is used in this assignment.

## Classes and Threading

SwipeEvent represents one generated ski event.

SwipeGenerator runs on one dedicated thread and places
500,000 events into an ArrayBlockingQueue. Generation finishes
before sender execution, so senders do not wait for the
generator during the measured ingestion phases.

IngestLoadClient coordinates generation, subscriptions,
warmup, the main phase, shutdown, and reporting.

Warmup uses 32 sender threads to send 32,000 events.
The main phase sends the remaining 468,000 events.
Main thread counts of 64, 128, and 256 were tested.

Each sender task receives a fixed event quota, polls events
from the shared queue, and calls SwipeSender. The coordinator
waits for all tasks to finish before ending a phase.

SwipeSender shares one HTTP/1.1 HttpClient across sender
threads, allowing persistent connection reuse. It uses
thread-safe counters and retries unsuccessful requests
up to five times with exponential backoff starting at 100 ms.

## WebSocket Strategy

Ten LeaderboardSubscriber instances subscribe to resorts
1 through 10 using a shared subscription HttpClient.

Each subscriber has a background worker that establishes
the connection, waits for disconnection, and reconnects
with backoff. Message callbacks assemble fragmented text,
parse JSON, and record update counts and timestamp differences.

Ingestion starts after every subscriber has received an
update. Subscribers remain active throughout ingestion
and are closed during cleanup.

Metrics include connection attempts, successful connections,
reconnections, connection establishment time, and updates.

## Measurement Design

SwipeCsvRecorder records each HTTP attempt with timestamp,
request type, latency, status code, and resort ID.
Writes are synchronized. Network failures use status code zero.

HTTP durations use System.nanoTime(), avoiding dependence
on clock alignment between the client and server.

LatencyStats computes mean, median, nearest-rank p95 and p99,
minimum, and maximum. SwipeCsvAnalysis reports attempt counts,
latency statistics, and successful throughput by resort.

ThroughputBuckets groups successful request completions
into 10-second intervals. The final partial interval uses
its actual duration. Completion times are approximated from
the attempt timestamp plus measured latency.

Generation, warmup, main ingestion, and total runtime are
reported separately. Main throughput excludes warmup.

## Little's Law and Performance

The measured sequential HTTP baseline mean was 37.171 ms.

Using L = lambda × W, with L approximated by the number
of synchronous sender threads:

Predicted throughput = sender threads / 0.037171 seconds.

Predictions are approximately:
32 threads: 861 events/s;
64 threads: 1,722 events/s;
128 threads: 3,444 events/s;
256 threads: 6,887 events/s.

Measured main-phase throughput was:
64 threads: 1,495.52 events/s;
128 threads: 2,691.50 events/s;
256 threads: 4,632.00 and 4,655.29 events/s.

The second 256-thread run achieved approximately 67.6%
of the baseline prediction. The estimate assumes constant
response time. Under load, response time, retries, scheduling,
logging, and resource limits may change. These measurements
do not isolate a single bottleneck.

## Connection Evidence and Limitations

A separate 256-thread run attached an X-Run-ID header.
Tomcat logged the client IP and port for matching requests.

The server recorded 500,417 requests across 2,843 distinct
client IP:port pairs, averaging 176.02 requests per pair.
Every observed pair handled multiple requests, supporting
connection reuse. These pairs are not an exact TCP connection
count, and HTTP retries are not reconnection counts.

Push latency uses the server timestamp and client receive
time, so cross-machine clock error affects the result.
Negative samples remained after Mac time synchronization.
Raw values were retained without filtering or correction.

Push timestamp differences therefore cannot be interpreted
as precise absolute one-way latency. Subscription-only clock
checks are reported separately from HTTP load tests.