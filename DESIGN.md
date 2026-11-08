# CS6650 Assignment 2 — System Design

## Architecture

Client → ALB → Ingest servers → RabbitMQ resort queues
→ Consumer aggregation → RabbitMQ fanout backplane
→ Ingest servers → Local WebSocket subscribers.

RabbitMQ and the Consumer run on separate EC2 instances.
The ingest tier was tested with one, two, and four instances.

## Ingestion and Queue Topology

Each ingest server validates requests, looks up the lift's vertical
drop, and publishes an event to skitrak.exchange, a topic exchange.
Routing key resort.{resortID} selects one of ten durable resort queues.

The producer reuses one publishing connection and borrows channels
from a thread-safe pool. Each borrowed channel has one owner at a time.
HTTP 201 is returned only after broker confirmation.
Connection recovery rebuilds the connection and channel pool.

Resort queues use a 24-hour TTL, a maximum of 100,000 ready messages,
and reject-publish overflow behavior. Dead-lettered events are routed
through skitrak.dlx to skitrak.expired.

## Processing Sequence

1. The client assigns an eventId and reuses it for retries.
2. The ingest server validates and publishes the event.
3. RabbitMQ confirms publication; the server returns HTTP 201.
4. The Consumer validates the delivery and checks eventId.
5. A new event updates the skier's resort/day vertical total.
6. If Top N changes, the Consumer publishes a leaderboard snapshot.
7. Each ingest server receives the snapshot and forwards it to its
   local subscribers.
8. The Consumer acknowledges the original swipe after successful
   processing and required snapshot publication.

Broker confirmation does not prove that a WebSocket client received
an update. HTTP completion and downstream completion are distinct.

## Connection Locality and Backplane

A WebSocket session belongs to the ingest server that accepted it.
The Consumer therefore publishes snapshots to leaderboard.fanout.

Each ingest server has its own temporary bound queue and forwards
updates to its local sessions. A shared queue would distribute updates
among servers rather than deliver a copy to every server.

This approach allows ingest servers to scale independently of resort
aggregation. Its costs include extra network traffic, subscriptions,
and snapshot management. Temporary backplane queues do not provide
durable replay across disconnects.

## Threading and Delivery

Resorts are distributed across configurable workers without overlapping
ownership. Each resort has one serial aggregation execution stream.
The current implementation caps active workers at ten.

Manual acknowledgements support redelivery after processing failure.
Local retries complete before later deliveries are processed.
eventId deduplication prevents duplicate aggregation within the
lifetime of the in-memory state.

Aggregation and deduplication state survive connection recovery within
the same process, but not a process restart. This implementation does
not provide durable exactly-once processing.

## Load Balancer and Deployment

The ALB accepts HTTP on port 80 and forwards to Tomcat on port 8080.
Health checks use /ski-servlet-v2/health with a 30-second interval,
5-second timeout, healthy threshold 2, and unhealthy threshold 3.
Cookie stickiness is configured for 300 seconds and idle timeout
for 120 seconds.

The health response includes publishReady, backplaneReady, and serverId.
Every ingest instance has a distinct SERVER_ID.

The Consumer is managed by systemd with startup and restart behavior.
Tomcat was started manually after instance restarts.
RabbitMQ uses Docker with a persistent data volume.

## Monitoring and Limitations

Client HTTP latency uses a monotonic clock. Consumer lag measures
event age from the ingest timestamp to the point before aggregation.
Push latency compares Consumer publication time with Mac reception time.

Negative push timestamp differences occurred in formal runs, so affected
push-latency summaries are invalid. Consumer lag also depends on
cross-host clock alignment.

Queue metrics are sampled every five seconds and can miss short peaks.
System metrics describe entire hosts, not individual processes.

Completed tests compare 500K events with one, two, and four ingest
servers at 64 sender threads, plus a four-instance 1M retest.
Increasing ingest count did not improve measured throughput at this
client concurrency; the experiments do not isolate a single bottleneck.

Dynamic Consumer scaling and the full requested thread matrix remain
incomplete. Matching counts and drained queues support no observed
loss in completed runs, not a guarantee under every failure condition.