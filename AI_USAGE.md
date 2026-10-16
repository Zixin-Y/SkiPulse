# AI Usage Reflection

## My Role and How I Used AI

I developed and tested this assignment through an iterative
process. I edited the Java source files, applied code changes,
compiled the projects, deployed the server to EC2, ran the
experiments, and collected the resulting logs and screenshots.

I used OpenAI Codex as a programming and troubleshooting
assistant. It suggested implementation approaches, supplied
code snippets and documentation drafts, and helped me interpret
errors and measurements. When I was unsure how to proceed,
I asked it to check whether the direction is right and explain 
the reasoning behind it.

My workflow was to apply a proposed change, run it in my own
environment, inspect the actual output, and decide whether
the change worked or needed correction. I did not treat an
AI response as sufficient evidence that the program was correct.

## Accepted Suggestion 1: Record HTTP Attempts Separately

### What AI Suggested

Codex suggested recording every HTTP attempt in a CSV file
and reporting successful responses separately from all attempts.
This would distinguish a temporary request failure from an
event that still failed after exhausting retries.

### What I Did

I wrote the CSV recording and analysis code to client-part2.
I compiled and ran the client, preserved the generated CSV
files, and reviewed the attempt counts, successful responses,
network errors, and retry counts.

### How I Verified It and Made a Decision

In the connection observation run, the client reported:

- Successful events: 500,000
- Final failed events: 0
- HTTP attempts: 501,904
- Retries: 1,904
- CSV rows: 501,904

The CSV row count matched the HTTP attempt count.
The attempt count also matched successful events plus retries
for this run. I therefore retained attempt-level recording
and kept final event failures separate from failed attempts.

This helped me interpret the red error messages correctly:
an individual attempt could fail while the event eventually
succeeded after a retry.

## Accepted Suggestion 2: Compare Concurrency Levels

### What AI Suggested

Codex suggested testing several sender thread counts and
keeping warmup measurements separate from the main phase.
It also helped me use Little's Law to form a throughput
prediction from the sequential HTTP baseline.

### What I Did

I configured and ran the load client with 64, 128, and 256
main-phase sender threads. I retained the same total workload
of 500,000 events and the 32-thread, 32,000-event warmup.

I also repeated the 256-thread experiment and saved the
logs, CSV files, screenshots, and throughput charts separately
so that results from different runs would not be mixed.

### How I Verified It and Made a Decision

The measured main-phase throughput was:

- 64 threads: 1,495.52 events/s
- 128 threads: 2,691.50 events/s
- 256 threads, Run 1: 4,632.00 events/s
- 256 threads, Run 2: 4,655.29 events/s

These runs completed 500,000 successful events with zero
final failed events. I used the second 256-thread run as
the main performance example and retained the other runs
as comparison evidence.

The sequential baseline mean was 37.171 ms, giving a
256-thread prediction of approximately 6,887 events/s.
I reported the difference between prediction and measurement
without claiming that the experiments identified one
specific bottleneck.

## Corrected Suggestion 1: A Method Name That Did Not Exist

### What AI Suggested

Codex supplied a short WebSocket test that called:

getPushLatencies()

### What I Did

I added the proposed code to SubscriptionPoolCheck and
attempted to compile and run it. Compilation failed with
"cannot find symbol" for that method.

I used the compiler output to identify the failing call.
After the actual LeaderboardSubscriber method was checked,
I changed the call to:

getPushLatenciesMs()

### How I Verified It and Made a Decision

I compiled and ran the test again. It completed with
exit code 0 and reported updates from all ten resorts.

This was a concrete AI mistake: the suggested method name
did not match my current code. I corrected the call rather
than assuming the generated snippet was compatible.

## Corrected Suggestion 2: An Outdated Server Address

### What AI Suggested

Codex provided example commands and run arguments using
an EC2 public IP address from an earlier stage of the work.

### What I Did

When the address changed, I checked the current EC2 public
IPv4 address and updated the client arguments and connection
commands. I also distinguished commands that belonged on
my Mac from commands that belonged inside the EC2 SSH session.

### How I Verified It and Made a Decision

I checked the server locally on EC2 using the health endpoint.
I then connected from my Mac using the current address.

The WebSocket command displayed "Connected" and received
leaderboard JSON messages with changing server timestamps.
I decided to supply the server address through program
arguments instead of relying on an old default value.

## Investigating a Measurement Problem: Negative Push Latency

### What AI Suggested

Codex helped me recognize that push timestamp differences
depend on clock alignment between the Mac and EC2.
It suggested checking both clocks and running a short
subscription-only test before repeating a full load test.

### What I Did

On my Mac, I checked automatic time settings, measured the
offset with sntp, and performed a time synchronization.

On EC2, I ran timedatectl status and chronyc tracking.
I then modified and ran a 15-second SubscriptionPoolCheck
that collected push timestamp differences and counted
negative samples.

### How I Verified It and Made a Decision

The initial Mac offset estimate was approximately 75.6 ms.
After synchronization, the estimate was approximately
0.437 ms, but its reported uncertainty was about 21 ms.

The subsequent subscription-only test received 310 updates
with zero reconnections, but 223 timestamp differences
were still negative.

I therefore did not treat time synchronization as proof
that the one-way latency measurement was accurate.
I retained the negative values, documented the limitation,
and kept this short test separate from the HTTP load runs.

I did not take absolute values, remove negative samples,
or apply an unverified fixed correction.

## Deployment, Verification, and Documentation Work

### What AI Suggested

Codex helped break deployment and submission into manageable
steps: build the WAR, deploy it to Tomcat, check health,
verify actual WebSocket messages, organize result evidence,
and prepare documentation and Git commits.

It also supplied draft README and design-report text.

### What I Did

I executed the deployment and verification commands,
edited configuration and source files, and collected the
outputs from my own runs.

I created and saved README.md and DESIGN.md, assembled
the report with screenshots and charts, and revised its
layout after review. I also created the GitHub repository,
initialized Git locally, committed the project, and pushed
the files.

### How I Verified It and Made a Decision

I used HTTP 200 health responses and actual WebSocket JSON
messages as deployment evidence. I checked that the design
section fit within two pages and kept results from different
experiments clearly identified.

For Git, I reviewed the ignore rules and staged file list,
then verified the successful push and the uploaded files
on the GitHub repository page.

The documentation drafts were based on the implementation
and measurements, rather than used as a substitute for
running the project.

## What I Learned About Using AI

AI was most useful when I needed help turning an error or
an unclear task into a sequence of checks. Compiler output,
health responses, CSV counts, logs, and repeat experiments
provided the evidence for accepting or correcting suggestions.

I also learned that successful execution does not guarantee
valid measurement. The WebSocket client worked correctly,
but clock uncertainty still limited the interpretation of
its push timestamp differences.

My responsibility was to carry out the implementation and
experiments, preserve the evidence, and explain both the
results and their limitations. AI supported that process
with code suggestions, explanations, and troubleshooting
directions.