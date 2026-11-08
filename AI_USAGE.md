# AI Usage Reflection — CS6650 Assignment 2

## How I Used AI

I used OpenAI Codex to help interpret assignment requirements,
discuss implementation choices, troubleshoot deployment issues,
prepare monitoring code, analyze experimental results, and draft
documentation.

Topics included RabbitMQ connection and channel management,
publisher confirmations, reconnection, eventId deduplication,
per-resort aggregation, the leaderboard broadcast backplane,
ALB configuration, and performance measurement.

I manually edited files in IntelliJ, executed commands in Mac
Terminal and EC2 SSH sessions, configured AWS resources, and
collected logs and screenshots. AI supplied suggestions and code
snippets; I compiled, deployed, and checked them against actual output.

## Accepted Suggestion 1: Broadcast Backplane

AI suggested using a RabbitMQ fanout exchange to distribute
Consumer-generated leaderboard snapshots to every ingest server.

I accepted this approach because a WebSocket connection belongs
to the server that accepted it. The Consumer cannot directly access
sessions held by another ingest server.

Each ingest server subscribes to the backplane and forwards updates
to its own local WebSocket subscribers.

I verified the approach by sending HTTP events through one ingest
server while subscribing through another and observing real
leaderboard updates.

The tradeoff is additional messaging and subscription management.
Broadcasting to every ingest server also creates work on servers
that may have no subscribers for a particular resort.

AI Chat History:
my message: “5. Is a broadcast backplane the better choice? What are co-located deployment, 
nodes, and stateful connections?”

AI message:: For your assignment, I lean toward the RabbitMQ broadcast backplane. 
You already have to deploy RabbitMQ, and you also need to compare 1, 2, 
and 4 ingest servers, so a backplane lets the ingest layer scale independently.


## Accepted Suggestion 2: Verify Processing Before Closing Subscribers

AI suggested keeping WebSocket subscriptions open after HTTP
sending completed.

I accepted this because receiving HTTP 201 means the broker confirmed
publication; it does not mean the Consumer has finished aggregation
or the client has received the resulting leaderboard update.

I added a manual verification step before closing subscriptions.
For completed runs, I checked the unique processed-event counter,
confirmed that the Consumer sourceId had not changed, and checked
that resort queues and the dead-letter queue were empty.

This is a manual completion check, not an automatic distributed
completion protocol.

AI Chat History:
my message: “Automatic time is already enabled,”
and the following assistant response discussing the client’s shutdown.

AI message:: As soon as HTTP sending finishes, the code enters finally and closes 
all WebSockets, which may cause later leaderboard updates to be missed.

## Corrected Suggestion 1: Direct Public-Port Testing

AI suggested temporarily changing the client from the ALB address
to an ingest server's public IP on port 8080.

The WebSocket connections timed out before HTTP load generation
started. I checked the health endpoint locally on EC2 and through
the ALB. Both worked, while direct public access timed out.

I continued through the working ALB endpoint rather than changing
security-group rules merely to follow the proposed test path.
The exact cause of the direct-access restriction was not established
by those checks alone.

I also kept the test labels consistent with the actual endpoint:
a run through the ALB with one healthy ingest server is not described
as a direct-to-instance run.

AI Chat History:
AI message: “Now run a 1,000-request validation test, 
connecting directly to ingest-1 and bypassing the ALB for now.”

my message: “This problem showed up after step 2. Should I continue
to use your way or should I continue to use my original way?” 
with WebSocket connection-timeout screenshots.

AI message: The revised instruction was:
No need to change the security group. For this round, switch back to 
the ALB and continue verifying lag. Earlier, I had you switch to a 
direct connection without first checking reachability, which added an 
unnecessary detour.


## Measurement Decision: Negative Push Latency

AI helped identify that subtracting timestamps from different machines
depends on clock alignment.

I checked EC2 synchronization with timedatectl and chronyc, checked
Mac automatic time settings, queried time.apple.com with sntp,
and manually synchronized the Mac clock between tests.

One subsequent small test had no negative push-latency samples,
but negative values returned in later experiments. Therefore,
I did not treat one successful clock check as a permanent solution.

I retained the raw negative values and marked affected push-latency
summaries invalid. I did not take absolute values, remove negative
samples, or add an unverified fixed correction.

I distinguished three measurements:

1. HTTP request latency measured with the client's monotonic clock.
2. Consumer lag from the ingest event timestamp to the point before
   aggregation begins.
3. Push timestamp differences from Consumer leaderboard publication
   to complete-message receipt on the Mac.

Consumer lag is also a cross-host measurement and has clock-related
uncertainty. Zero negative samples do not prove exact accuracy.

## Design Decision: Ordering and Consumer Concurrency

I preserved one serial aggregation execution stream per resort
instead of distributing one resort's events across workers that
could apply them out of order.

With ten resort queues, the current implementation caps effective
resort aggregation concurrency at ten. Configuring a larger requested
thread count does not establish that 20, 40, or 80 aggregation streams
were active.

I do not describe this fixed worker assignment as dynamic scaling.
Dynamic Consumer scaling and the complete Assignment 2 thread
experiment matrix remain incomplete.

## Handling an Interrupted Experiment

During an earlier 1M attempt, the EC2 instances underwent a stop/start
cycle. SSH sessions disconnected, public IP addresses changed, and
the Consumer restarted.

Because aggregation and deduplication state are in memory, the new
Consumer process could not provide a continuous counter for the
interrupted run.

I retained that attempt separately rather than reporting it as a
completed successful test. After restoring services, verifying four
healthy ALB targets, and recording a new Consumer baseline, I ran a
separate 1M retest with a new day ID and new result filenames.

## Interpreting the Performance Results

AI helped summarize client logs, queue samples, Consumer counters,
and system-resource measurements.

At 64 main-phase sender threads, increasing the ingest server count
from one to two or four did not improve throughput in the measured
500K runs. I retained these results rather than claiming that horizontal
scaling necessarily improved performance.

The two-server access logs verified balanced request distribution.
That evidence rules out all requests going to one server for that run,
but it does not independently identify the throughput bottleneck.

Matching HTTP success counts, Consumer unique-count increments,
and final drained queues support that no loss was observed in the
completed runs. They do not prove loss-free behavior under every
failure condition.

## Documentation and Verification

AI provided drafts for README, design explanations, result summaries,
and this reflection. I reviewed these against the implementation
and preserved experimental evidence.

I kept Assignment 1 measurements separate from Assignment 2 results.
Unfinished requirements and measurement limitations are documented
rather than presented as completed work.

The source code, logs, CSV files, and screenshots provide the evidence
for the claims made in the report.

## What I Learned

AI was useful for breaking complex deployment and debugging problems
into concrete checks. However, its suggestions sometimes used an
incorrect filename, assumed network access, or required adaptation
to the current project.

I learned to distinguish expected output from observed evidence,
SSH disconnection from instance restart, HTTP acceptance from completed
processing, and successful execution from valid measurement.

My responsibility is to understand and defend the submitted code,
verify suggestions, preserve honest results, and explain the system's
limitations.


