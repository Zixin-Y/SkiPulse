# 6650 ASSIGNMENT2

<!-- English translation of the original Chinese Codex transcript, source lines 1–15065; original commands and embedded file contents are reproduced verbatim. -->

> 1. What role does Tomcat play?
> 2. Where does the thread-safe pool fit in the overall flow diagram? Does it come before the RabbitMQ connection?
> 3. Where in the overall flow does listening happen?
> 4. What files will we create for Assignment 2, and what does each one do? List them in the order we'll create them so I can see the big picture.
> 5. When we talk about a connection dropping, do we mean what happens if it unexpectedly disconnects? Is that why we have step three and the recovery steps that follow?
> 6. In “Use a thread-safe blocking queue to store idle channels,” what does “blocking queue” mean?
> 7. Which part of the system do the connection and channel refer to?
> 8. The assignment has a practical constraint: there are only 10 ski resorts, and processing within each resort has to be serial, so at most 10 resorts can aggregate simultaneously. What do “serial processing within a resort” and “aggregation within a resort” mean?
> 9. What does the consumer do in this assignment? Does it maintain the leaderboard and track each skier's cumulative vertical feet?
> 10. How does a server become healthy again after it goes unhealthy? Can I do something, or do I just wait for the server to recover on its own?
> 11. What is an exchange? Is it the same as fanout?

These questions all come back to the same issue: **what runs where, who receives, transports, and processes data, and who handles recovery after a failure.** Let's map out the components first, then go through each question.

```text
Your Mac
└─ Load-test client
       │ HTTP request
       ▼
AWS ALB load balancer
       │ Forwards requests
       ▼
Ingress server EC2
└─ Tomcat
   └─ Your Java web application
      ├─ SkierServlet: validates lift swipe requests
      ├─ SwipePublisher: publishes messages
      ├─ ConnectionManager: manages RabbitMQ connections
      ├─ ChannelPool: manages idle channels
      └─ LeaderboardEndpoint: holds WebSocket connections
             │
             │ Connection containing multiple Channels
             ▼
RabbitMQ EC2
├─ Swipe-event exchange
├─ 10 ski resort queues
└─ Leaderboard broadcast exchange and broadcast queues
             ▲
             │ Connection containing a Channel
             │
Consumer EC2
└─ Consumer application
   ├─ Receives swipe messages
   ├─ Deduplicates events, totals vertical feet, and updates leaderboards
   └─ Publishes leaderboard updates
```

These are the file names we plan to use. The files haven't been created yet.

### 1. What role does Tomcat play?

**Tomcat is the container that runs your server-side Java web application.**

You can't run a `main()` method in `SkierServlet.java` like an ordinary program and have it start receiving HTTP requests. Tomcat handles the following:

- Listening on a network port, such as `8080`.
- Receiving HTTP requests.
- Finding the servlet that should handle each request.
- Assigning a request thread to run your code.
- Sending your code's response back to the client.
- Supporting WebSocket connections.
- Managing application startup, shutdown, and redeployment.

For example:

```text
HTTP lift swipe request
    ↓
Tomcat receives the request
    ↓
Finds SkierServlet based on the request path
    ↓
Runs doPost()
    ↓
Your code validates the request and publishes a RabbitMQ message
    ↓
Tomcat returns the HTTP response
```

Think of Tomcat as the workplace and the servlet as the staff member who handles the work there.

**EC2 is the machine, Tomcat is software running on that machine, and your web application runs inside Tomcat.**

A standalone consumer application can usually start directly from `main()` without Tomcat.

### 2. Where does the thread-safe pool fit? Does it come before the RabbitMQ connection?

**The channel pool lives inside the Java application on the ingress server, where the publisher uses it.**

It isn't a separate server that network requests have to pass through, and describing it as coming “before the connection” is too simplistic.

In **creation order**:

```text
Create a Connection
    ↓
Use the Connection to create multiple Channels
    ↓
Put the idle Channels into the pool
```

In **request execution order**:

```text
SkierServlet
    ↓
SwipePublisher
    ↓
Borrow a Channel from ChannelPool
    ↓
Publish the message through that Channel
    ↓
The Channel uses its Connection to communicate with RabbitMQ
    ↓
Return the Channel when finished
```

So:

- **Establish the connection before creating the channels and assembling the pool.**
- **Borrow a channel from the pool before publishing a message.**
- The pool holds channel objects, not swipe messages.

A consumer usually keeps the channel it uses to subscribe to a queue for the lifetime of that subscription. It does not borrow and return a channel for every message.

### 3. Where in the flow does listening happen?

Listening is an action: **you register handling logic in advance, and it runs when something happens.** There is no single box in the architecture that represents listening.

There are at least these kinds of listeners in the assignment:

| What it listens for | Where it runs | What it does when triggered |
|---|---|---|
| Application starts or stops | Lifecycle listener inside Tomcat | Initializes or shuts down connections, the channel pool, and background tasks |
| RabbitMQ connection closes unexpectedly | Connection manager | Marks it unavailable and triggers recovery |
| A queue receives a swipe message | Consumer application | Deduplicates the event, totals vertical feet, and updates the leaderboard |
| A broadcast queue receives a leaderboard update | Ingress server | Pushes the update to local WebSocket subscribers |
| A WebSocket opens, closes, or encounters an error | `LeaderboardEndpoint` | Adds or removes subscribers |

For example, a connection shutdown listener works like this:

> Tell the client library up front: “If this connection closes unexpectedly, call my failure handler.”

You don't have to write a loop that continually checks whether the connection has dropped.

Also distinguish the two mechanisms: **ALB health checks actively probe the service at intervals, while a connection shutdown listener receives a failure notification.**

### 4. What files will we create for Assignment 2, and in what order?

I understand “files” to include **the project's source files, configuration files, scripts, and submission documents**.

Here is the suggested order. The names may change slightly during implementation. **Finish a small, verifiable piece of functionality at each stage before moving on.**

| Order | Files to create or modify | Purpose |
|---|---|---|
| 1 | `README.md`, example configuration | Document the structure, how to run the project, and the configuration options. Keep it updated. |
| 2 | `AI_USAGE.md` | Record how you actually use AI and the judgments you make from the start, then organize those notes at the end. |
| 3 | `server-v2/`, `consumer/pom.xml` | Keep Assignment 1 and set up the ingress and standalone consumer projects. |
| 4 | `SwipeMessage.java`, `LeaderboardUpdate.java` | Define the data structures for swipe and leaderboard messages. |
| 5 | `LiftVerticalLookup.java` | Look up each lift's vertical drop in the official mapping table. |
| 6 | RabbitMQ configuration or initialization script | Set up exchanges, queues, bindings, TTL, and capacity limits. |
| 7 | `RabbitMqConnectionManager.java` | Establish a shared connection, manage connection state, and recover after disconnections. |
| 8 | `ChannelPool.java` | Create, lend, take back, and replace publishing channels. |
| 9 | `SwipePublisher.java` | Publish swipe messages to RabbitMQ and handle publisher confirms and failures. |
| 10 | `ApplicationLifecycleListener.java`, edit `web.xml` | Tie resource initialization and shutdown to the Tomcat lifecycle. |
| 11 | Edit `SkierServlet.java` | Change the flow from validating and returning immediately to validating, looking up vertical drop, publishing, and returning. |
| 12 | `ConsumerApplication.java`, consumer configuration | Start the standalone consumer application and load its settings. |
| 13 | `ResortConsumerManager.java`, `ResortWorker.java` | Subscribe to each resort's queue, distribute work, and maintain processing order within a resort. |
| 14 | `ProcessedEventStore.java`, `LeaderboardAggregator.java` | Deduplicate events, total vertical feet, and maintain the top N. |
| 15 | `LeaderboardPublisher.java` | Publish real leaderboard updates to the broadcast exchange. |
| 16 | `LeaderboardBackplaneSubscriber.java` | Let each ingress server receive its own copy of the broadcast. |
| 17 | Edit `LeaderboardEndpoint.java` | Remove simulated data and push real leaderboard updates. |
| 18 | Edit relevant client files | Use stable event IDs, parameterize load tests, check subscription readiness, and wait for processing to finish. |
| 19 | Correctness and failure verification programs | Verify totals, duplicate handling, reconnection, and cross-server pushes. |
| 20 | `ScalingController.java` | Add load detection and thread adjustment once basic processing works correctly. |
| 21 | Deployment scripts, service configuration, and ALB setup instructions in `deployment/` | Deploy multiple instances and configure automatic restarts, target groups, and health checks. |
| 22 | Metrics collection scripts in `monitoring/` | Collect queue, application, system, and ALB metrics. |
| 23 | Test logs, screenshots, and results analysis | Save evidence from runs with 1, 2, and 4 instances and from load testing. |
| 24 | Architecture documentation and the final submission PDF | Bring together diagrams, design, results, and repository links. |

We'll determine how to share common code such as connections and message models when we organize the Maven projects, so you won't have to maintain two separate copies.

These 24 items fit into six stages:

```text
Set up the project and define messages
    ↓
Publish swipe events reliably
    ↓
Consume events correctly and calculate leaderboards
    ↓
Broadcast real leaderboards across servers
    ↓
Deploy and test recovery and performance
    ↓
Prepare the submission materials
```

### 5. Does a dropped connection mean we need to recover after an unexpected disconnection?

**Yes. The recovery flow discussed earlier is mainly for unexpected disconnections.**

Examples include:

- A temporary network interruption.
- A RabbitMQ restart.
- A failure on the EC2 instance running RabbitMQ.
- A communication error that closes the connection.

The channels on that connection can no longer work normally. Recovery involves restoring usable channels and relevant subscriptions as well as reconnecting.

```text
Normal operation
    ↓
Detect unexpected disconnection
    ↓
Mark resources unavailable
    ↓
Start a recovery task
    ↓
Retry the connection, backing off after failures
    ↓
Connection succeeds; rebuild channels and required subscriptions
    ↓
Resume normal operation
```

Distinguish **unexpected disconnection** from **intentional shutdown**:

> If you're stopping the application, the program should close the connection and exit instead of detecting the closure and automatically reconnecting.

The connection manager therefore also needs to know whether the application is stopping.

Sometimes an operation error closes only one Channel while the Connection stays healthy. In that case, replacing the failed channel is enough.

### 6. What does “blocking queue” mean?

**A blocking queue is a thread-safe container that can make the calling thread wait when an operation cannot complete immediately.**

Two common cases:

- The queue is empty and a thread wants to take an item. It waits until someone puts one in.
- The queue is full and a thread wants to put in an item. It waits until there is space.

In our channel pool, it holds **idle Channels**:

```text
The pool has only C1 and C2

Thread A borrows C1
Thread B borrows C2

Thread C tries to borrow:
Pool is empty → waits

Thread A returns C1
Thread C wakes up → borrows C1
```

“Blocking” means the thread temporarily waits for a resource, usually without wasting CPU repeatedly checking for it.

For HTTP requests, we will **wait with a timeout**. If a channel stays unavailable too long, the request will return a clear failure instead of hanging indefinitely.

There are two different queues here:

| Queue | Where it lives | What it holds |
|---|---|---|
| Blocking queue used by the channel pool | In the Java application's memory | Idle Channel objects |
| RabbitMQ queue | In the RabbitMQ service | Swipe events or leaderboard messages |

### 7. Which part of the system do the Connection and Channel refer to?

They refer to **communication between your Java application and RabbitMQ**.

```text
Java application on the ingress server
    │
    └─ Connection: network connection to RabbitMQ
         ├─ Channel 1: publishes events
         ├─ Channel 2: publishes events
         └─ Channel 3: publishes events
                         │
                         ▼
                    RabbitMQ
```

The consumer application also opens its own Connection and uses Channels to subscribe, acknowledge, or publish messages.

So:

- Mac → ingress server: HTTP connection.
- Subscribing client → ingress server: WebSocket connection.
- Ingress server → RabbitMQ: the RabbitMQ Connection we're discussing.
- Consumer application → RabbitMQ: another RabbitMQ Connection.
- A Channel is a logical channel inside a RabbitMQ Connection, not a separate TCP connection.

**A Channel is also distinct from a queue.** One channel can operate on multiple queues. There is no inherent one-to-one relationship.

### 8. What do “serial processing within a resort” and “aggregation within a resort” mean?

“Within a resort” means **within the same ski resort**.

**Serial processing within a resort: apply events for the same resort to its state one at a time.**

For example, suppose resort 1's queue contains these events in order:

```text
E1 → E2 → E3
```

We process them in this order:

```text
Finish the state update for E1
    ↓
Finish the state update for E2
    ↓
Finish the state update for E3
```

E2 cannot overtake E1 just because it takes less time to process. The order here comes from the queue. Events from multiple producers are not automatically sorted by their business timestamps.

**Aggregation within a resort: combine events from the same ski resort into results.**

For example, for the same date and resort:

| Event | Skier | Vertical feet for this ride |
|---|---|---:|
| E1 | 100 | 200 |
| E2 | 200 | 300 |
| E3 | 100 | 150 |

After aggregation:

| Skier | Cumulative vertical feet |
|---|---:|
| 100 | 350 |
| 200 | 300 |

Use those totals to generate the leaderboard.

Different resorts can process events at the same time:

```text
Resort 1: E1 → E2 → E3
Resort 2: F1 → F2 → F3
Resort 3: G1 → G2 → G3
```

Here is what “at most 10” means: **with a separate serial state-update process per resort in this design, 10 resorts can perform at most 10 within-resort aggregations concurrently.** Other threads can still handle network communication, broadcasting, monitoring, and similar tasks.

### 9. Does the consumer handle the leaderboard and cumulative vertical feet?

**Yes. That's its core business logic, and it also helps ensure reliable message processing.**

A swipe message generally goes through these steps:

1. Receive the message and check its contents.
2. Check whether its `eventId` has already been processed.
3. If it is new, update the skier's cumulative vertical feet.
4. Update the top N for the resort and date.
5. If the leaderboard changes, publish an update.
6. Acknowledge the message once the required processing is complete.
7. If processing fails, follow the appropriate recovery or retry strategy.

The consumer stores state by these dimensions:

```text
Cumulative vertical feet: resort + date + skier
Leaderboard: resort + date
```

The consumer generally **does not hold users' WebSocket connections directly**. It publishes leaderboard updates, and the ingress servers push them to users.

Even when the same people stay in the top N, a change to one skier's total can change the leaderboard contents and require an update.

### 10. How does a server become healthy again? Will it recover if I wait?

**“Unhealthy” is ALB's assessment of the check results.** ALB keeps probing, but it generally won't restart Tomcat or fix a configuration mistake for you.

Find the cause first:

| Cause | How service recovers |
|---|---|
| Temporary network disruption | Subsequent checks may succeed once the network recovers. |
| Tomcat process exits | A service manager restarts it automatically, or you restart it manually. |
| Application fails to start or has a bug | Fix the code or configuration and redeploy. |
| Security group blocks health-check requests | Fix the security group rules. |
| Health-check path is wrong | Correct the configured path. |
| RabbitMQ disconnection leaves the ingress service unready | Health checks can succeed once the application's reconnection logic restores the dependency. |

The overall flow is:

```text
Failure occurs
    ↓
Health checks fail and ALB marks the server unhealthy
    ↓
Automatic recovery or manual intervention fixes the cause
    ↓
Health checks succeed consecutively
    ↓
ALB marks the server healthy
```

**Waiting only helps if the underlying cause goes away.** A permanent error won't fix itself over time.

We'll configure processes such as the consumer to restart automatically. A successful process restart does not guarantee that processing has recovered correctly, so we still have to consider state recovery separately.

### 11. What is an exchange? Is it the same as fanout?

**An exchange is a RabbitMQ component that distributes messages according to rules. Fanout is one type of exchange.**

It's like the relationship between a vehicle and a bus:

> “Vehicle” is a category. A “bus” is one type of vehicle.

A producer typically sends a message to an exchange, which sends it to queues according to its bindings.

This assignment primarily uses two types of exchanges:

| Type | Distribution method | Use in this assignment |
|---|---|---|
| `topic` | Matches queues based on the routing key and binding patterns | Routes swipe events to the corresponding ski resort |
| `fanout` | Sends a copy to every bound queue | Broadcasts leaderboard updates to every ingress server |

**Swipe events:**

```text
Swipe message with routing key resort.3
    ↓
skitrak.exchange [topic type]
    ↓
Match the binding rule
    ↓
Resort 3's queue
```

**Leaderboard broadcast:**

```text
Leaderboard update
    ↓
leaderboard.fanout [fanout type]
    ├─ Server A's broadcast queue → A's subscribers
    ├─ Server B's broadcast queue → B's subscribers
    └─ Server C's broadcast queue → C's subscribers
```

Keep these three concepts distinct:

- An **exchange** distributes messages.
- A **binding** specifies how an exchange is connected to a queue.
- A **queue** holds messages until a consumer processes them.

You can choose the exchange's name. Calling it `leaderboard.fanout` does not make it a fanout exchange by itself. You must specify the type when creating it.

You can check your understanding by tracing a single swipe: **Tomcat receives the request → the servlet validates it → the publisher borrows a channel → the message goes through the Connection to the exchange → it enters a queue → the consumer aggregates the data → the update is broadcast → the ingress server pushes it.** Each file handles a small part of this flow.

> Okay, I understand now. Please guide me through getting started on the assignment. Should I copy Assignment 1 first? Also, every time you create a file, tell me what it's for and give me a general explanation of what each section of code does and means.

<details><summary>4 previous messages</summary>

> Yes. Keep Assignment 1 and create a separate `assignment2` project so future changes won't affect the original submission. For this step, I'll set up the directories and copy the code. Whenever I create or modify a file, I'll explain what it does, what each section does, and how to verify it.
>
> <details><summary>Ran 2 commands</summary>
>
> - Ran `pwd; rg --files -g 'AGENTS.md' -g 'pom.xml' -g 'README.md' -g '!target/**' . '/Users/zixin/Downloads/6650'`
> - Ran `ls -la; for p in /Users/AGENTS.md /Users/zixin/AGENTS.md /Users/zixin/Documents/AGENTS.md /Users/zixin/Documents/Codex/AGENTS.md /Users/zixin/Documents/Codex/2026-11-01/AGENTS.md /Users/zixin/Downloads/AGENTS.md /Users/zixin/Downloads/6650/AGENTS.md; do if [ -f "$p" ]; then cat "$p"; fi; done; cat '/Users/zixin/Downloads/6650/assignment1/client-part2/pom.xml'; ls -la '/Users/zixin/Downloads/6650/assignment1/server'`
>
> </details>
>
> I'll put the new project in `outputs/assignment2` in the current workspace and copy the server source and the more complete `client-part2`. I'll leave the old test results, build outputs, and IntelliJ settings out of the copy. The original folder in Downloads will stay as it is. This step establishes a clear, verifiable starting point before adding RabbitMQ code.
>
> <details><summary>Ran a command</summary>
>
> - Ran `python3 - <<'PY'
> from pathlib import Path
> import shutil
> src=Path('/Users/zixin/Downloads/6650/assignment1')
> dst=Path('/Users/zixin/Documents/Codex/2026-11-01/fan/outputs/assignment2')
> if dst.exists():
>     raise SystemExit('Destination already exists; stopped without overwriting.')
> dst.mkdir()
> for old,new in [('server','server-v2'),('client-part2','client')]:
>     (dst/new).mkdir()
>     shutil.copy2(src/old/'pom.xml', dst/new/'pom.xml')
>     shutil.copytree(src/old/'src', dst/new/'src', ignore=shutil.ignore_patterns('.DS_Store'))
>     if (src/old/'web').exists():
>         shutil.copytree(src/old/'web', dst/new/'web', ignore=shutil.ignore_patterns('.DS_Store'))
> for name in ['consumer','deployment','monitoring','results']:
>     (dst/name).mkdir()
> (dst/'README.md').write_text('''# CS6650 Assignment 2
>
> ## 当前状态
>
> 本项目从 Assignment 1 的服务端和 client-part2 源码复制而来。
> 目前尚未实现 RabbitMQ、真实聚合、广播背板或 ALB 部署。
> server-v2 仍直接确认有效刷卡，排行榜仍使用模拟数据。
>
> ## 目录
>
> - server-v2/：接入服务与 WebSocket 服务，基于原 server。
> - client/：压测及订阅客户端，基于原 client-part2。
> - consumer/：预留给独立消费者应用，当前为空。
> - deployment/：预留给部署配置和脚本，当前为空。
> - monitoring/：预留给监控脚本，当前为空。
> - results/：预留给本次作业的真实测试结果，当前为空。
> - AI_USAGE.md：持续记录实际 AI 使用情况。
>
> ## 继承的环境要求与待修改项
>
> 原项目使用 Java 11、Maven、Tomcat 9。
> 目前 server-v2 与 client 是两个独立 Maven 项目；仓库根目录没有 pom.xml。
> 复制未改变 Maven 标识、WAR 名称或 URL 路径。
> 客户端仍包含旧的默认 EC2 地址、输出目录和固定负载参数；运行前需要修改。
> 客户端的订阅就绪条件和结束等待逻辑也需要在接入真实排行榜时调整。
> 本步骤只验证复制完整性，尚未进行编译、部署或云端验证。
>
> ## 实现顺序
>
> 1. 确认官方缆车落差表和消息结构。
> 2. 实现共享连接、通道池和可靠发布。
> 3. 实现消费者、去重、累计高度和有序聚合。
> 4. 实现排行榜广播背板和真实 WebSocket 推送。
> 5. 适配客户端并验证正确性和故障恢复。
> 6. 完成动态线程管理、多实例部署、监控与压测。
> 7. 汇总架构文档、测试证据和提交 PDF。
> ''', encoding='utf-8')
> (dst/'AI_USAGE.md').write_text('''# AI 使用记录（持续更新）
>
> ## 已发生的使用
>
> - 使用 AI 阅读 Assignment 1 源码，解释 Assignment 2 的要求并拆分实现步骤。
> - 使用 AI 解释 Tomcat、Connection、Channel、通道池、消费者、广播背板、确认与重投等概念。
> - 使用 AI 建立 Assignment 2 项目起点：复制 server 和 client-part2 源码，创建目录与说明文件；本阶段未改动 Java 代码。
>
> ## 待由本人记录和反思
>
> 以下内容尚未完成，不代表已经发生的判断：
>
> - 至少两条实际接受的建议，以及本人接受的理由。
> - 至少两条实际拒绝或纠正的建议，以及本人判断的理由。
> - 一次 AI 错误，或一个由本人独立推理的设计决策。
> - 如分享聊天记录，补充真实链接或导出文件位置。
>
> 提交前请按真实经历补充，不要将待办事项写成已发生的经历。
> ''', encoding='utf-8')
> (dst/'.gitignore').write_text('''# Java 构建产物
> **/target/
> **/out/
> *.class
>
> # 本地 IDE 与系统文件
> .idea/
> *.iml
> .DS_Store
>
> # 本地凭据与配置（可提交不含真实凭据的示例文件）
> .env
> .env.*
> !.env.example
> *.pem
> *.key
> ''', encoding='utf-8')
> count=0
> for old,new in [('server','server-v2'),('client-part2','client')]:
>     for f in (dst/new).rglob('*'):
>         if f.is_file():
>             original=src/old/f.relative_to(dst/new)
>             assert original.read_bytes()==f.read_bytes(), str(f)
>             count+=1
> print(f'Created {dst}\nVerified {count} copied files are byte-identical to Assignment 1.')
> PY`
>
> </details>
</details>

**Yes. Start by making a separate copy of Assignment 1. I've already completed that setup, so you don't need to copy it manually.** We'll add features one at a time, explaining and verifying each step before going on.

New project location: [assignment2 folder](./outputs/assignment2)

The original `/Users/zixin/Downloads/6650/assignment1` was left unchanged.

### What I completed this time

```text
assignment2/
├── server-v2/     ← Copied from Assignment 1's server
├── client/        ← Copied from Assignment 1's client-part2
├── consumer/      ← Empty directory for the consumer implementation
├── deployment/    ← Empty directory for deployment configuration
├── monitoring/    ← Empty directory for monitoring scripts
├── results/       ← Empty directory for Assignment 2 test results
├── README.md
├── AI_USAGE.md
└── .gitignore
```

I copied only source code, Maven configuration, and web configuration. The copy excludes old test results, build outputs, and IntelliJ settings. I verified that **all 30 copied files are byte-for-byte identical to the originals**. I haven't compiled or run the project yet.

### What each new file does

**1. [README.md](./outputs/assignment2/README.md): project instructions**

Its sections explain:

- **Current status:** The project still behaves like Assignment 1. The message queues and real leaderboards have yet to be implemented.
- **Directories:** Where to find code, deployment configuration, monitoring scripts, and results.
- **Environment requirements and items to update:** The Java, Maven, and Tomcat requirements, along with inherited settings such as old addresses and output paths.
- **Implementation order:** The roadmap for the remaining development work.

This helps you and anyone who later reads the repository understand the project. We'll update it as the implementation progresses.

**2. [AI_USAGE.md](./outputs/assignment2/AI_USAGE.md): record of actual AI use**

It has two sections:

- **AI use so far:** Records the earlier source code review, concept explanations, and project setup.
- **Items for you to record and reflect on:** Reminds you to add actual examples of accepting, rejecting, or correcting suggestions.

I haven't made up any examples of you accepting or rejecting suggestions. We'll record what happens as we work, and you'll organize it at the end based on your own understanding.

**3. [.gitignore](./outputs/assignment2/.gitignore): tells Git which local files to leave out**

It has three groups of rules:

- **Build outputs:** Ignores `target/`, `out/`, and `.class` files, which can be regenerated.
- **IDE and system files:** Ignores local IntelliJ settings and miscellaneous macOS files.
- **Local credentials and configuration:** Ignores `.env` and private keys, among other files, while allowing an `.env.example` with no real credentials to be committed.

### How I'll guide you from here

Whenever I add or modify code, I'll explain:

1. **Where the file goes and why we need it.**
2. **What calls it and how it connects to other files.**
3. **What each part of the code does, including fields, methods, threads, and exception handling.**
4. **How to verify it and what a successful result looks like.**

This round included no changes to the Java code. Next we'll establish **the message structure and the lift vertical-drop table**, then implement connection management and the channel pool. We can't make up the official vertical-drop table, so we'll first check whether the materials you provided already include it.

> But I'm using IntelliJ, so please show me how to do it manually.

Understood. **You'll do the work in IntelliJ, and I'll guide you step by step.** When you need code, I'll show you where to create the file, what to paste, and what each section does. I'll leave the edits to you.

The copy I just created is in a different workspace. Your Assignment 1 is untouched, and you can set that copy aside for now.

**For this step, just copy and open the project.**

### Step 1: Copy the project in Finder

1. Open Finder and go to `/Users/zixin/Downloads/6650/`.
2. Select the `assignment1` folder.
3. Press `Command + D` to duplicate it.
4. Rename the copy `assignment2`.

The two folders should now be side by side:

```text
6650/
├── assignment1/
└── assignment2/
```

From here on, only edit `assignment2`.

### Step 2: Organize the copy

Open `assignment2` and make these changes:

| Original file or folder | What to do | Why |
|---|---|---|
| `server` | Rename it `server-v2` | Match the Assignment 2 directory requirement |
| `client-part2` | Keep the name | Reuse the client with more complete functionality |
| `client-part1` | Delete from the copy | This assignment only needs one client to maintain |
| `results` | Keep the folder and remove the old results inside | Avoid mistaking Assignment 1 results for Assignment 2 results |
| `assignment1-report.pdf`, `.docx` | Delete from the copy | You'll create a new report for Assignment 2 |
| `README.md`, `DESIGN.md`, `AI_USAGE.md` | Keep them | You'll update them as you go. They still describe Assignment 1 for now. |

**Before deleting anything, confirm that the Finder path shows `assignment2`.**

Then create three empty folders:

```text
consumer
deployment
monitoring
```

The project should look roughly like this:

```text
assignment2/
├── server-v2/
├── client-part2/
├── consumer/
├── deployment/
├── monitoring/
├── results/
├── README.md
├── DESIGN.md
└── AI_USAGE.md
```

If the copy contains a `.git` directory, leave it alone for now. We'll set up version control separately later.

### Step 3: Open Assignment 2 in IntelliJ

1. In IntelliJ, choose **File → Open**.
2. Select the entire `assignment2` folder.
3. Choose **New Window** so you can easily distinguish it from Assignment 1.
4. If IntelliJ asks whether you trust the project, confirm that it's the local project you just copied and choose to trust it.

In the Project pane on the left, confirm that you can see:

```text
server-v2/pom.xml
client-part2/pom.xml
```

These files manage dependencies and builds for the server and client, respectively. It's normal for the root directory to have no `pom.xml` at this point.

### Step 4: Confirm that the Maven projects are loaded

Open the **Maven** tool window on the right and check whether both the server and client Maven projects appear.

If they don't:

1. Find `server-v2/pom.xml` on the left.
2. Right-click it and choose **Add as Maven Project**.
3. Repeat for `client-part2/pom.xml`.

The names Maven shows after copying may still include `ski-servlet` or `client-part2`. Those names come from `pom.xml`. **Renaming the folders doesn't automatically change the Maven identifiers.** Leave the names as they are for now.

This step requires no coding and no EC2 or RabbitMQ startup. Once you're done, tell me whether both projects appear in IntelliJ's Maven window. If you run into an issue, tell me which step you reached and what message appeared, and we'll continue from there.

> 1. Why don't we need to maintain `client-part1` this time? What does it do?
> 2. Does everything look right in the screenshots?
> 3. Where is Maven? Why doesn't it appear in the pane on the right? What should I do?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-c96c2dac-37fe-4681-be84-be85d11c7c9c.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-414c2ee1-aa8c-4e62-b04b-66e3ca7b8639.png`

<details><summary>2 previous messages</summary>

> From the screenshots, the directory structure looks ready. I'll explain the difference between the two clients and then show you how to load both `pom.xml` files as Maven projects. This step involves no code changes.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `site.jetbrains.com/help/idea Maven tool window Add as Maven Project pom.xml`
>
> </details>
</details>

**Your folder structure is correct. Now confirm that IntelliJ recognizes both `pom.xml` files as Maven projects.**

### 1. Why don't we need to maintain `client-part1`?

Your two original clients correspond to the two stages of Assignment 1:

| Client | What it does |
|---|---|
| `client-part1` | Basic load-testing client that generates swipe events, sends them from multiple threads, retries requests, and subscribes over WebSocket |
| `client-part2` | Adds CSV logging, latency statistics, throughput analysis, and other features to the basic functionality |

Based on the code we reviewed earlier, `client-part2` already includes the core sending and subscription features we'll reuse in Assignment 2, plus more complete performance measurements.

So **for Assignment 2, you only need to keep developing `client-part2`**. This avoids making the same changes in two places. `client-part1` is still available in the original `assignment1` if you need to refer to it.

Here, `part2` means the second part of Assignment 1. It hasn't already implemented Assignment 2.

### 2. Do the screenshots look right?

**Yes, the directory setup is complete:**

- You have `server-v2` and `client-part2`.
- You have `consumer`, `deployment`, `monitoring`, and `results`.
- You kept the three Markdown documents.

A few things in the screenshots can wait:

- `assignment2 [assignment1]` likely reflects the IntelliJ project name or module metadata inherited from the copy. The copy can still be fine.
- `cs6650.assignment1` in `pom.xml` is the original Maven identifier. You can update the identifiers together later.
- `.idea`, `target`, and `out` contain IDE settings or generated files. Leave them alone for now.

**The screenshots alone don't confirm that Maven loaded successfully.** IntelliJ can open a `pom.xml` file in the editor without importing it as a Maven project.

### 3. Where is Maven? Follow these steps

Maven manages **dependencies and builds** for Java projects. The Maven window in IntelliJ is the interface for working with it.

**First, try adding the projects:**

1. In the Project pane on the left, find the **`pom.xml` file** under `client-part2`.
2. Right-click the file in that pane. The editor tab at the top has a different menu.
3. Choose **Add as Maven Project**.
4. Wait for IntelliJ to load the project and download the dependencies.
5. Repeat for `server-v2/pom.xml`.

This is JetBrains' method for recognizing an existing Maven project. [Official troubleshooting instructions](https://youtrack.jetbrains.com/projects/MPS/articles/SUPPORT-A-4229/How-to-troubleshoot-if-Maven-Project-is-not-recognized-by-IntelliJ-IDEA)

**Then open the Maven window:**

In the macOS menu bar at the top of the screen, choose **View → Tool Windows → Maven**.

Once the window opens, you should see two projects, usually named:

```text
client-part2
ski-servlet
```

It's normal for the server to appear as `ski-servlet`. That name comes from the `artifactId` in `pom.xml` and may differ from the `server-v2` folder name. [Maven tool window documentation](https://www.jetbrains.com/help/idea/maven-projects-tool-window.html)

**If the right-click menu has no “Add as Maven Project” option and the menus don't list Maven:**

1. Open **IntelliJ IDEA → Settings → Plugins**.
2. Search for `Maven` under **Installed**.
3. Check whether the built-in **Maven** plugin is enabled. If needed, enable it and restart when prompted.
4. Return to the project and try the steps above again.

For now, the goal is to **see both projects in the Maven window**. You can leave `install` and `pom.xml` alone. If you can't find the right-click option, send a screenshot of that menu and I'll guide you based on what you see.

> Okay, that worked. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-99177f64-10b0-43b2-81b5-37d3b577ab10.png`

Yes, the screenshot shows both **`client-part2` and `ski-servlet`** in Maven, so the import worked.

Next, do a **clean compile** to confirm that the copied project builds before adding any Assignment 2 code. You can do this without starting EC2, Tomcat, or RabbitMQ.

**1. Compile the server**

In the Maven window on the right:

1. Expand the arrow next to `ski-servlet`.
2. Expand **Lifecycle**.
3. Double-click **clean** and wait for it to finish.
4. Double-click **compile**.

Both operations should show this in the output pane at the bottom:

```text
BUILD SUCCESS
```

Here's what each operation does:

| Operation | What it does |
|---|---|
| `clean` | Deletes old build artifacts in `target` so you don't accidentally use files carried over in the copy |
| `compile` | Compiles the current source code and checks that the dependencies, Java configuration, and code can build successfully |

`clean` leaves the source code in `src` intact. `compile` leaves the server stopped.

**2. Compile the client**

Repeat the operation for `client-part2` in the Maven window:

1. Expand **Lifecycle**.
2. Double-click **clean** and wait for it to finish.
3. Double-click **compile**.
4. Check for `BUILD SUCCESS`.

**This step is complete when `compile` succeeds for both projects.** If either one fails, send the error text or a screenshot before making configuration changes.

For the upcoming lift vertical lookup, we'll need the instructor's **official table of vertical drops for all 40 lifts**. Check the course starter repo for a relevant file. We haven't found the table in the Assignment 1 code reviewed so far, and we can't make up the data ourselves.

Compile both projects first and tell me the results. Then we'll create the first message model file and go through the code section by section.

> Does this look right now?
> Also, the screenshots show the assignment requirements. Do they say where to find the official vertical-drop table for the 40 lifts?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-99329e57-c060-405a-90e5-979f57eee2a2.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-21950bca-619c-4b1e-a778-420fffbff03b.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-9637cc2f-a3e3-442f-ad1a-414d641cfbca.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-84b4436e-296b-4dc3-a692-a59fe8c79103.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4f6f754e-7444-4112-992e-7babe918c455.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-627c98b6-866d-4946-847a-fc2a78f836b3.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-9f10ff0f-cb73-48ad-91e0-e4bf907f282f.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-7648f924-d376-44d4-8e40-3a82c3d86d19.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-519fb6c1-2800-4aee-807a-9dc64ccf3ad0.png`

**Yes. The first screenshot shows that the server project, `ski-servlet`, compiled successfully: `BUILD SUCCESS`.** The current server source builds. Starting or deploying the server is a separate step.

This screenshot doesn't show the client build result. If **`client-part2 → Lifecycle → compile`** also reports `BUILD SUCCESS`, you've completed the entire step.

For the **official vertical-drop table for the 40 lifts**, the second screenshot says this under **Part 1 → Ingest Server Becomes a Producer**:

> The lift → vertical table is provided in the starter repo.

This means that **the mapping from lift ID to vertical drop is in the course's starter-code repository.**

The screenshots **don't give a specific file name, path, or repository link**. Here's what we can confirm:

- There are 40 lifts.
- Each lift has a fixed vertical drop.
- The drops range from 60 to 400 feet.
- The sample message's `"vertical": 200` is just an example. It doesn't tell us the values in the table.

Here's how to look for it:

1. Click **`cs6650-fall-2026`** at the top of the GitHub page to return to the repository homepage.
2. Check whether the homepage `README` links to **starter code** or a **starter repo**.
3. Look in the repository's code or data directories for file names containing `lift` or `vertical`.
4. You can also search the GitHub repository for `vertical` and review matches in code and configuration.

**Send me the full course repository link and I can help locate the table.** If it requires a course account, you can send a screenshot of the repository's file list and we can look together.

We can define the message model while looking for the table. We'll need the official data before implementing the lookup and verifying cumulative totals.

> Is the starter repo shown here?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-34c155e8-823e-4cfd-948c-0f2866e7350d.png`

**The screenshot doesn't directly show a starter repo.** It shows the main course repository, whose top level contains:

- `assignments`
- `labs`
- `quiz/quiz_1`
- `slides`
- `README.md`

“Starter repo” means the repository containing the starting code. It may be a link to a separate repository, and there may be no folder with that name.

Press **`Command + F`** on the current page and search for **`starter`** to see whether the README below contains a relevant link. If it doesn't, open the **`assignments`** folder and look for starter code or a link alongside the assignment instructions.

**We can only tell that the screenshot doesn't show it. It could still be elsewhere in the repository.** Hold off on making up your own vertical-drop table. If you can't find it, ask the instructor or TA where it is.

> I haven't found the table yet. Can you guide me through the earlier steps first?

Yes. **We can define the message structure, manage connections, and implement the channel pool before we have the table.** Once we find it, we'll add the vertical-drop lookup and complete the publishing flow.

Start by creating **`SwipeMessage.java`**, which represents a lift swipe message that will be sent to RabbitMQ.

Create just this one file in this round so you can understand it and verify that it compiles.

**Step 1: Find where to create the file**

In the left pane of IntelliJ, expand:

```text
server-v2
└── src
    └── main
        └── java
            └── model
```

The existing `model` package should already contain `LiftRide` and `ResponseMsg`.

Right-click **`model` → New → Java Class** and enter:

```text
SwipeMessage
```

Choose **Class**. IntelliJ adds the `.java` extension for you.

The resulting file path is:

```text
assignment2/server-v2/src/main/java/model/SwipeMessage.java
```

**Step 2: Enter the code**

Replace the contents of the new file with:

```java
package model;

public final class SwipeMessage {

    // 1. 一次刷卡事件的数据
    private final String eventId;
    private final String resortID;
    private final String dayID;
    private final String skierID;
    private final String liftID;
    private final int vertical;
    private final int time;
    private final String timestamp;
    private final String serverId;

    // 2. 创建消息时，一次性提供所有字段
    public SwipeMessage(
            String eventId,
            String resortID,
            String dayID,
            String skierID,
            String liftID,
            int vertical,
            int time,
            String timestamp,
            String serverId) {

        this.eventId = eventId;
        this.resortID = resortID;
        this.dayID = dayID;
        this.skierID = skierID;
        this.liftID = liftID;
        this.vertical = vertical;
        this.time = time;
        this.timestamp = timestamp;
        this.serverId = serverId;
    }

    // 3. 允许其他代码读取消息字段
    public String getEventId() {
        return eventId;
    }

    public String getResortID() {
        return resortID;
    }

    public String getDayID() {
        return dayID;
    }

    public String getSkierID() {
        return skierID;
    }

    public String getLiftID() {
        return liftID;
    }

    public int getVertical() {
        return vertical;
    }

    public int getTime() {
        return time;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getServerId() {
        return serverId;
    }
}
```

**Step 3: Understand the file**

Here's where it fits in the flow:

```text
HTTP request
    ↓
SkierServlet validates the request and looks up the vertical drop
    ↓
Create a SwipeMessage object       ← The class created in this step
    ↓
The publisher serializes the object to JSON
    ↓
Send it to RabbitMQ through a Channel
```

**This file defines the message data model.** Connecting to RabbitMQ, looking up the vertical drop, and sending the message happen elsewhere. That keeps each file's responsibilities clear.

**① `package model;` declares the package**

The class belongs to the `model` package, which corresponds to its directory. Code in another package can use it by adding `import model.SwipeMessage;`.

**② `public final class SwipeMessage` defines the class**

- `public` allows code in other packages to use it.
- `class` defines the structure of an object.
- `final` prevents subclassing. The message model doesn't need to be extended through inheritance at this point.

**③ The nine fields correspond to the required JSON**

| Field | Meaning | Where its value will come from |
|---|---|---|
| `eventId` | Unique ID for one logical swipe | A UUID that stays the same across retries |
| `resortID` | Ski resort ID | Request path |
| `dayID` | Day identifier | Request path |
| `skierID` | Skier ID | Request path |
| `liftID` | Lift ID | Request body |
| `vertical` | Vertical feet for this ride | Official lift vertical-drop table |
| `time` | Swipe time value as defined in the assignment | Request body |
| `timestamp` | Time the message was created, in ISO-8601 format | The ingest server's clock |
| `serverId` | Which ingest server created the message | Server configuration |

The IDs are `String` values, while `vertical` and `time` are `int` values. This matches the assignment's message format. If the original request provides `liftID` as a number, you can convert it to a string while constructing the message.

`time` represents the logical time within the simulated ski day. `timestamp` represents the actual date and time.

Each field uses:

- `private` to prevent direct access or modification by other classes.
- `final` to prevent reassignment once initialized.

Combined with the absence of setters, this keeps business logic from changing the object after creation and makes it easier to pass the object between threads.

**④ The constructor creates a complete message**

It has the same name as the class:

```java
public SwipeMessage(...)
```

When the ingest server creates a message with `new SwipeMessage(...)`, it must supply nine values.

For example:

```java
this.eventId = eventId;
```

On the left, `this.eventId` is the field stored in the object. On the right, `eventId` is the parameter passed in. The assignment stores the parameter in the object.

**We generate the UUID outside the constructor.** The calling code needs to control event ID creation and retries together so that a retry doesn't get a different ID.

The class currently stores data. The ingest server still validates the request, and the consumer will also need to validate messages it receives.

**⑤ Getters read the fields**

For example:

```java
public int getVertical() {
    return vertical;
}
```

This allows other code to read `vertical`. There is no `setVertical()` to change the value.

The aggregator will use this value to increase the cumulative vertical feet. The publisher can use the project's existing Gson dependency to serialize the fields to JSON.

**Step 4: Verify by compiling**

Save the file, then go to the Maven window on the right:

```text
ski-servlet → Lifecycle → compile
```

Double-click `compile` and confirm that you see:

```text
BUILD SUCCESS
```

You can skip `clean` this time. **A successful build confirms that the message model has been added correctly. RabbitMQ messages aren't sent at this stage.**

Tell me the result when you're done. Next, we'll configure the RabbitMQ Java client dependency and implement connection management step by step.

> The original `model` package should already contain `LiftRide` and `ResponseMsg`, but they don't appear in my screenshot. Is that okay?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-cd7bee56-f131-41f8-bad4-3fe9f910dcac.png`

**The `model` package is collapsed in the screenshot. Its contents are hidden until you expand it.**

Click the **small `>` arrow next to `model`** in the left pane. You should see:

```text
model
├── LiftRide
└── ResponseMsg
```

The `EchoClient` and `SkierServlet` classes you see are at the same level as `model`, outside that package.

Once you've expanded it and checked, **right-click `model` → New → Java Class → enter `SwipeMessage`**, then paste the code from the previous message.

If the two classes really are missing after you expand the package, send a screenshot of the expanded package. Hold off on recreating `LiftRide` and `ResponseMsg`.

> Okay. From now on, please write the comments in the code in English and the detailed explanations outside the code in Chinese.

Okay, I'll use this format going forward:

- **Code comments and documentation comments in English.**
- **Instructions, file purposes, section-by-section explanations, and verification steps outside the code in Chinese.**

Replace the three Chinese comments in the earlier `SwipeMessage.java` with these, in order:

```java
// 1. Data for a single swipe event
```

```java
// 2. Initialize all fields when creating the message
```

```java
// 3. Provide read access to the message fields
```

The rest of the code can stay as it is.

> Done. Please show me what to do next.

<details><summary>2 previous messages</summary>

> Next, we'll add the RabbitMQ Java client dependency to the server. I'll check the official dependency information, then walk you through editing `pom.xml`, loading the dependency, and compiling. You can leave RabbitMQ stopped for this step.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/client-libraries/java-client`
>
> </details>
</details>

Next, **add the RabbitMQ Java client dependency to `server-v2`** so the code can use classes such as `Connection` and `Channel`.

If you haven't compiled your new `SwipeMessage.java` yet, run `compile` on the server project first and confirm that you see `BUILD SUCCESS`.

**1. Open the server's `pom.xml`**

In the Project pane on the left, open:

```text
server-v2 → pom.xml
```

Edit the server's POM. The client's POM is at `client-part2/pom.xml`.

This file tells Maven:

- The project's name, version, and packaging type.
- Which Java version it uses.
- Which third-party libraries it needs.
- How to compile and package it.

**2. Add the dependency configuration**

Find the existing:

```xml
<dependencies>
```

Add this on the next line, before the existing Servlet dependency:

```xml
        <!-- RabbitMQ Java client for AMQP connections and channels -->
        <dependency>
            <groupId>com.rabbitmq</groupId>
            <artifactId>amqp-client</artifactId>
            <version>5.36.0</version>
        </dependency>
```

This is the Maven dependency configuration from the official RabbitMQ documentation. The 5.x client supports Java 8 and later, so you can use it in your Java 11 project. [Official documentation](https://www.rabbitmq.com/client-libraries/java-client)

After adding it, that part of the file should look roughly like this:

```xml
    <dependencies>

        <!-- RabbitMQ Java client for AMQP connections and channels -->
        <dependency>
            <groupId>com.rabbitmq</groupId>
            <artifactId>amqp-client</artifactId>
            <version>5.36.0</version>
        </dependency>

        <!-- Servlet API -->
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <version>4.0.1</version>
            <scope>provided</scope>
        </dependency>

        <!-- Keep the other existing dependencies here. -->
```

**Keep all existing dependencies and closing tags below this section. Insert the new dependency into the existing `<dependencies>` block.**

**3. Understand the new configuration**

| Item | What it means |
|---|---|
| `<!-- ... -->` | An XML comment explaining the purpose to whoever reads the code |
| `<dependency>` | Declares that the project needs a third-party library |
| `com.rabbitmq` | Identifies the organization that provides the library, as its `groupId` |
| `amqp-client` | Identifies the library, as its `artifactId` |
| `5.36.0` | Pins the version for reproducible builds |

With no explicit `scope`, Maven uses the default `compile` scope. The library is available at compile time and normally included when Maven packages the WAR.

Your existing Servlet API dependency is marked `provided` because Tomcat supplies it at runtime. The application needs to bundle the RabbitMQ client.

**Adding the dependency makes the client library available.** Installing the RabbitMQ broker and opening a connection are separate steps. We'll implement connection reuse, channel pooling, and reconnection later.

**4. Have IntelliJ load the dependency**

Save the file, then click **Reload All Maven Projects** at the top of the Maven window on the right. You can hover over the refresh icon to confirm the tooltip before clicking.

Wait for the download to finish. The first load requires internet access.

**5. Verify by compiling**

Double-click:

```text
Maven → ski-servlet → Lifecycle → compile
```

The step is complete when:

- The output shows `BUILD SUCCESS`.
- You can find `com.rabbitmq:amqp-client:5.36.0` under `ski-servlet → Dependencies`.

The only file changed this time is `server-v2/pom.xml`. Next, we'll create a connection configuration class to keep the RabbitMQ address, credentials, channel pool size, and other settings together.

> 1. What's the difference between `server-v2` and `client-part2`? What does each one do?
> 2. I successfully added the RabbitMQ dependency. What's next?

**`server-v2` is the server under test. `client-part2` sends requests to it and measures the results.** Now that you've added the RabbitMQ dependency, we'll create the server configuration class.

### 1. What does each project do?

| Aspect | `server-v2` | `client-part2` |
|---|---|---|
| Role | Server that receives and processes requests | Test client that generates load and verifies results |
| Typical runtime location | Inside Tomcat on EC2 | Your Mac |
| Receives swipe requests | Yes | Sends the requests |
| Publishes swipe messages to RabbitMQ | Yes | No |
| Holds a user's WebSocket connection | Yes, as the WebSocket server | Yes, as the subscribing client |
| Measures load-test throughput and request latency | Will provide server-side metrics | Responsible for the main client-side measurements |

A single swipe flows like this:

```text
client-part2
    │ Sends an HTTP swipe request
    ▼
server-v2
    │ Validates the request and publishes a message
    ▼
RabbitMQ
    ▼
consumer
```

Leaderboard updates flow back like this:

```text
consumer calculates the leaderboard
    ↓
RabbitMQ broadcasts the update
    ↓
server-v2 pushes it over WebSocket
    ↓
client-part2 receives and records it
```

**`client-part2` doesn't interact directly with RabbitMQ**, so we only added the dependency to `server-v2`. The standalone `consumer` project will need its own RabbitMQ dependency later.

### 2. Next, create `RabbitMqConfig.java`

This file **loads RabbitMQ settings in one place** so you don't have to repeat the IP address, username, password, and channel pool size across files.

It provides configuration data. Opening connections and reconnecting are responsibilities of other components:

```text
RabbitMqConfig
    │ Provides the address, credentials, and pool size
    ▼
Future connection manager and channel pool
```

We'll supply the settings through **environment variables**. This lets you change the RabbitMQ address or adjust the pool size without editing the Java source.

**Step 1: Create the `messaging` package**

Find this path in the left pane:

```text
server-v2 → src → main → java
```

Right-click **`java` → New → Package** and enter:

```text
messaging
```

This package holds code for message communication. The `model` package holds the data models.

**Step 2: Create the class**

Right-click **`messaging` → New → Java Class** and enter:

```text
RabbitMqConfig
```

The result should look like this:

```text
server-v2/src/main/java/
├── model/
│   └── SwipeMessage.java
└── messaging/
    └── RabbitMqConfig.java
```

**Step 3: Enter the code**

```java
package messaging;

public final class RabbitMqConfig {

    // Connection settings and publisher channel pool size
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final int channelPoolSize;

    // Create a configuration object with all required settings
    private RabbitMqConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            int channelPoolSize) {

        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.channelPoolSize = channelPoolSize;
    }

    // Load settings from the process environment
    public static RabbitMqConfig fromEnvironment() {
        String host = requiredEnv("RABBITMQ_HOST");
        int port = positiveIntEnv("RABBITMQ_PORT", 5672);

        if (port > 65535) {
            throw new IllegalArgumentException(
                    "RABBITMQ_PORT must be between 1 and 65535"
            );
        }

        String username = requiredEnv("RABBITMQ_USERNAME");
        String password = requiredEnv("RABBITMQ_PASSWORD");
        String virtualHost = envOrDefault("RABBITMQ_VHOST", "/");
        int poolSize = positiveIntEnv("RABBITMQ_CHANNEL_POOL_SIZE", 16);

        return new RabbitMqConfig(
                host, port, username, password, virtualHost, poolSize
        );
    }

    // Require a nonblank value without logging its contents
    private static String requiredEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required environment variable: " + name
            );
        }

        return value;
    }

    // Use the fallback only when the variable is not defined
    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    // Parse a positive integer and report configuration errors clearly
    private static int positiveIntEnv(String name, int fallback) {
        String value = envOrDefault(name, Integer.toString(fallback));

        try {
            int parsed = Integer.parseInt(value);

            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report the same error for malformed and out-of-range integers.
        }

        throw new IllegalArgumentException(
                name + " must be a positive integer"
        );
    }

    // Provide read access without allowing settings to be changed
    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public int getChannelPoolSize() {
        return channelPoolSize;
    }
}
```

### 3. What each section does

**① Package and class declarations**

```java
package messaging;
```

This places the class in the messaging package.

`public final class` lets code in other packages use the class and prevents inheritance.

**② The six fields store configuration values**

| Field | Environment variable | Purpose |
|---|---|---|
| `host` | `RABBITMQ_HOST` | IP address or domain name of the RabbitMQ host |
| `port` | `RABBITMQ_PORT` | Connection port, defaulting to `5672` |
| `username` | `RABBITMQ_USERNAME` | RabbitMQ username |
| `password` | `RABBITMQ_PASSWORD` | RabbitMQ password |
| `virtualHost` | `RABBITMQ_VHOST` | Logically isolated space within RabbitMQ, defaulting to `/` |
| `channelPoolSize` | `RABBITMQ_CHANNEL_POOL_SIZE` | Number of publishing channels in the pool, initially defaulting to `16` |

`16` is a starting value. Adjust it based on testing. **It does not have to match the number of HTTP threads.**

A `virtualHost` is an isolated workspace within RabbitMQ, with its own exchanges, queues, and permissions. It does not require a separate EC2 instance.

**③ The private constructor controls how instances are created**

The constructor is `private`, so other classes can't call `new RabbitMqConfig(...)` directly.

They get the configuration through:

```java
RabbitMqConfig.fromEnvironment()
```

That ensures all settings are read and checked in one place.

**④ `fromEnvironment()` assembles the configuration**

This method:

1. Reads the required address, username, and password.
2. Reads the port and checks its range.
3. Reads the virtual host and pool size, using the defaults when they are absent.
4. Returns a complete configuration object.

`static` means you can call the method before creating a configuration object.

**⑤ `requiredEnv()` checks required settings**

`System.getenv(name)` reads a value from **the current Java process's environment**.

If a required setting is missing or blank, the method throws an exception naming the variable. The error message does not include the password value.

This surfaces configuration mistakes during initialization, before load testing.

**⑥ `envOrDefault()` handles optional settings**

If the variable is undefined, the method uses the default. If it's defined, the method uses the supplied value.

These variables aren't loaded automatically from a `.env` file. For local runs, you can enter them in the IntelliJ run configuration. After deployment, you can pass them through the service configuration that starts Tomcat.

**⑦ `positiveIntEnv()` validates numeric settings**

It ensures that the port and pool size can be parsed as positive integers.

- `"16"` works.
- `"abc"` cannot be parsed as an integer.
- `"0"` and `"-1"` aren't positive integers.
- Numbers outside Java's `int` range also produce an error.

The `try/catch` handles parsing failures and produces a consistent, clear configuration error.

**⑧ Getters let other components read the settings**

For example, the connection manager reads the host address with:

```java
config.getHost()
```

There are no setters, so the settings can't be reassigned after the configuration object is created. The password getter is for establishing the connection. Keep its return value out of the logs.

### 4. How do we verify it now?

Save the file, then run:

```text
Maven → ski-servlet → Lifecycle → compile
```

Once you see `BUILD SUCCESS`, this step is complete.

**You can leave the real IP address, username, and password unset for now.** Compiling does not run `fromEnvironment()`, and we haven't connected it to application startup yet.

Next, we'll implement the shared connection manager so it can use these settings to connect to RabbitMQ.

> 1. What are the files in `server-v2`'s `model` package for? What do `LiftRide` and `ResponseMsg` do? Why are we putting `RabbitMqConfig` under the Java folder instead of in `model`?
> 2. What does this dialog mean? Should I click Add?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d3e44ff1-b39d-464d-b41f-5b038b300ec4.png`

**Click `Cancel` for now. The screenshot shows something to fix first: you may have created a file named `messaging.java`, but we need a package named `messaging`.**

### 1. What does each file in `model` do?

The `model` package holds **classes that describe the application's data structures**. These classes belong to different stages of request processing:

```text
HTTP request body
    ↓
LiftRide: holds the swipe parameters
    ↓
SkierServlet: validates and adds other information
    ↓
SwipeMessage: complete event for the queue
    ↓
RabbitMQ

SkierServlet → ResponseMsg → HTTP response
```

| File | Purpose | Example data |
|---|---|---|
| `LiftRide.java` | Represents the swipe request body submitted by the client | `time`, `liftID` |
| `ResponseMsg.java` | Represents a message returned by the server to the client | Messages such as “invalid JSON body” |
| `SwipeMessage.java` | Represents the complete swipe event sent to RabbitMQ | Event ID, resort, skier, vertical drop, time, and so on |

**Why does `LiftRide` contain only some of the information?**

The HTTP request carries data in two places:

```text
Request path: supplies resortID, dayID, skierID, and so on
Request body: supplies time and liftID
```

`LiftRide` represents the request body. The server combines it with the path parameters, the vertical drop it looks up, the event ID, and other information to make a `SwipeMessage`.

**Why is `ResponseMsg` a separate class?**

It lets the server use Gson to serialize messages into a consistent JSON response without manually concatenating JSON strings throughout the code. Sending RabbitMQ messages is outside its role.

### 2. Where should `RabbitMqConfig` go?

The exact location is:

```text
src/main/java/
├── model/
│   ├── LiftRide.java
│   ├── ResponseMsg.java
│   └── SwipeMessage.java
└── messaging/
    └── RabbitMqConfig.java
```

**Place it in the `messaging` package under `java`.**

The packages have different responsibilities:

- `model` defines the data structures for business requests, responses, and events.
- `messaging` holds RabbitMQ communication components such as settings, connection management, and channel pooling.

We put `RabbitMqConfig` in `messaging` because it holds connection settings. Java leaves this organizational choice to us.

`src/main/java` is Maven's default Java source root.

### 3. What does the dialog mean? Should I click Add?

The dialog is asking:

> Do you want to add the newly created file to Git version control?

- **Add** tells Git to track the file. Committing the file and uploading it to GitHub are separate actions.
- **Cancel** leaves the file untracked for now. It remains available to edit and compile.

For a correctly created source file, you can usually click **Add**. The path in this screenshot, however, is:

```text
src/main/java/messaging.java
```

That suggests you chose **New → Java Class** and created a class called `messaging` where you intended to create a package.

**Here's how to fix it:**

1. Click **Cancel** in the dialog.
2. Find `messaging.java` in the left pane.
3. If it's just the empty class you created and you haven't pasted any code into it, right-click and delete it. If you already pasted code, copy it someplace safe first.
4. Right-click the **`java` folder in `src/main/java` → New → Package**.
5. Enter **`messaging`**.
6. Right-click the new **`messaging` package → New → Java Class**.
7. Enter **`RabbitMqConfig`**.
8. If IntelliJ asks whether to add `RabbitMqConfig.java` to Git, click **Add**.
9. Paste the code from the previous reply. The first line should be:

```java
package messaging;
```

Verify that the left pane shows **the `RabbitMqConfig` class inside the `messaging` package**, then run the server's `compile` goal to check it.

> Should I click Add now?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bc091e96-f5c8-43e2-be7f-1ec7449725b5.png`

**Yes, click `Add`.** This tells Git to track the source file. It does not upload it to GitHub.

The screenshot shows the package name as **`message`**, and we previously chose **`messaging`**. Make the names consistent:

1. Click **Add**.
2. Right-click the `message` package in the left pane, choose **Refactor → Rename**, and rename it to `messaging`.
3. Confirm that the first line becomes:

```java
package messaging;
```

Replace the current empty class with the complete `RabbitMqConfig` code from earlier, save it, and run:

**Maven → ski-servlet → Lifecycle → compile**

When you see `BUILD SUCCESS`, this step is complete.

> What happened here? I wanted to rename `message` to `messaging`, and this appeared after I clicked Refactor. What should I do?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-943bd8cb-dd96-424a-a5dc-8c1cf93da7e0.png`

This is the normal **Refactoring Preview**. IntelliJ is asking you to review the proposed changes before it applies them.

The screenshot shows that it plans to:

- Rename the package directory from `message` to `messaging`.
- Change `package message;` in the code to `package messaging;`.

**Click `Refactor` next to `Cancel` at the bottom left to apply the rename.**

Afterward, confirm that the structure looks like this:

```text
java
├── messaging
│   └── RabbitMqConfig
└── model
```

The first line of `RabbitMqConfig.java` should now be:

```java
package messaging;
```

Then paste in the complete `RabbitMqConfig` code from earlier, save, and run the server's `compile` goal.

> `RabbitMqConfig` now builds successfully. What should I do next?

<details><summary>2 previous messages</summary>

> Next, we'll create a shared connection manager to open, reuse, and close the RabbitMQ connection. I'll check the client API first. This round covers the basic connection lifecycle. We'll add the channel pool and automatic reconnection later to keep the amount of new code manageable.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/client-libraries/java-api-guide`
>
> </details>
</details>

Great. Next, create **`RabbitMqConnectionManager.java`, the shared connection manager**.

For now, implement three basic operations:

1. Open a connection.
2. Return the same connection for the channel pool to use later.
3. Close the connection when the application stops.

**We'll add automatic reconnection after wiring in the channel pool.** Recovery will need to rebuild both the connection and the pool together. A successful build at this stage does not complete the reconnection requirement.

### 1. Create the file

In the left pane in IntelliJ, **right-click the `messaging` package → New → Java Class**.

Enter:

```text
RabbitMqConnectionManager
```

If the Git dialog appears, click **Add**.

The structure should look like this:

```text
messaging/
├── RabbitMqConfig.java
└── RabbitMqConnectionManager.java
```

### 2. Enter the code

```java
package messaging;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class RabbitMqConnectionManager implements AutoCloseable {

    // Log lifecycle events without exposing credentials.
    private static final Logger LOGGER =
            Logger.getLogger(RabbitMqConnectionManager.class.getName());

    // Keep one factory and one shared connection per manager instance.
    private final ConnectionFactory factory;
    private Connection connection;
    private boolean closed;

    // Configure the factory without opening a network connection.
    public RabbitMqConnectionManager(RabbitMqConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());

        factory.setConnectionTimeout(10_000);
        factory.setRequestedHeartbeat(30);

        // Recovery will be coordinated with channel pool rebuilding.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
    }

    // Open the initial connection once during application startup.
    public synchronized void start()
            throws IOException, TimeoutException {

        if (closed) {
            throw new IllegalStateException(
                    "Connection manager has already been closed"
            );
        }

        if (connection != null) {
            if (connection.isOpen()) {
                return;
            }

            throw new IllegalStateException(
                    "RabbitMQ connection is closed; recovery is required"
            );
        }

        Connection opened =
                factory.newConnection("skitrak-ingest");

        connection = opened;

        opened.addShutdownListener(cause -> {
            if (!cause.isInitiatedByApplication()) {
                LOGGER.warning(
                        "RabbitMQ connection closed unexpectedly"
                );
            }
        });

        LOGGER.info("RabbitMQ connection established");
    }

    // Return the existing connection without creating another one.
    public synchronized Connection getConnection() {
        if (closed || connection == null || !connection.isOpen()) {
            throw new IllegalStateException(
                    "RabbitMQ connection is not available"
            );
        }

        return connection;
    }

    // Report the current connection state for lifecycle checks.
    public synchronized boolean isConnected() {
        return !closed
                && connection != null
                && connection.isOpen();
    }

    // Close the connection when the application stops.
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        if (connection != null) {
            connection.close();
        }
    }
}
```

### 3. Where this file fits in the flow

```text
RabbitMqConfig
    │ Supplies the connection settings
    ▼
RabbitMqConnectionManager     ← Implemented in this step
    │ Opens and stores one Connection
    ▼
ChannelPool                  ← Next step
    │ Uses this Connection to create multiple Channels
    ▼
SwipePublisher               ← Implemented later
```

“One shared connection” means **the application creates one manager instance and reuses the connection it holds**. We'll create the manager at application startup and reuse it across HTTP requests.

### 4. What each section does

**① `import` statements bring in the required types**

| Type | Purpose |
|---|---|
| `Connection` | A RabbitMQ connection |
| `ConnectionFactory` | Opens a connection using the supplied settings |
| `IOException` | Represents an I/O failure, such as a network error |
| `TimeoutException` | Indicates that an operation, such as opening a connection, timed out |
| `Objects` | Used here to check that an argument is non-null |
| `Logger` | Records events such as successful connections and unexpected shutdowns |

`implements AutoCloseable` means this class manages a resource that must be closed and provides a `close()` method. **We'll still need a lifecycle listener to call it when Tomcat shuts down.**

RabbitMQ's official examples use `ConnectionFactory` to open connections and recommend reusing long-lived connections. [Official Java API guide](https://www.rabbitmq.com/client-libraries/java-api-guide)

**② Three instance fields store the manager's state**

```java
private final ConnectionFactory factory;
private Connection connection;
private boolean closed;
```

- `factory` creates connections and stays the same after initialization.
- `connection` stores the current shared connection and starts as `null`.
- `closed` tracks whether the manager was explicitly closed and starts as `false`.

`connection` can be reassigned so that recovery can replace it with a new connection later.

`closed` distinguishes these two cases:

> A network failure dropped the connection, so we need to recover.

> The application is stopping, so close resources and exit.

**③ The constructor configures the factory**

```java
public RabbitMqConnectionManager(RabbitMqConfig config)
```

It takes the configuration object we just created and passes the address, port, credentials, and other settings to the factory. It doesn't connect to the network.

The two settings below use different units:

```java
factory.setConnectionTimeout(10_000);
factory.setRequestedHeartbeat(30);
```

- `10_000` is a connection timeout of 10,000 **milliseconds**, or 10 seconds.
- `30` is a requested heartbeat timeout of 30 **seconds**. The client and server negotiate the actual value and use heartbeats to help detect failed connections.

Heartbeats help detect problems. Establishing a new connection requires separate recovery logic.

The following two lines disable automatic recovery so we can coordinate connection and channel pool recovery ourselves later:

```java
factory.setAutomaticRecoveryEnabled(false);
factory.setTopologyRecoveryEnabled(false);
```

Keep these settings in place while implementing the manual recovery required by the assignment.

**④ `start()` opens the initial connection**

It handles these cases:

- The manager is already closed. Refuse to start.
- An open connection already exists. Return immediately and reuse it.
- The previous connection has dropped. At this stage, explicitly report that recovery is required.
- No connection has ever been created. Ask the factory to open one and store it.

As long as the connection is healthy, repeated calls to `start()` reuse it.

```java
throws IOException, TimeoutException
```

This passes connection failures to the caller. The startup and recovery logic will handle these exceptions later.

**⑤ `synchronized` prevents conflicting management operations**

If two threads call `start()` together without coordination, both could see that there is no connection and each open one.

`synchronized` makes these methods mutually exclusive **on the same manager instance**, preventing that initialization race.

Network interruptions can still happen, and work done through the returned connection has its own thread-safety requirements. **The channel pool still has to ensure exclusive use of each Channel.**

**⑥ The shutdown listener detects and records unexpected disconnections**

```java
opened.addShutdownListener(cause -> {
    ...
});
```

This registers a callback. The RabbitMQ client invokes it when the connection closes.

`cause -> { ... }` is Java lambda syntax. Read it as:

> When the shutdown cause is passed in, run the code inside the braces.

For now, it logs a warning when the application did not initiate the shutdown. We'll schedule recovery here later.

**⑦ `getConnection()` reuses the existing connection**

This method returns the stored connection without calling `newConnection()`.

The channel pool will use it to get the shared connection and create multiple Channels.

The connection can still drop after the check. Publishing code must handle failures during the actual operation as well as checking `isOpen()`.

**⑧ `isConnected()` checks the current state**

All three conditions must hold:

- The manager hasn't been closed.
- A connection has been created.
- The connection is currently open.

`&&` means “and.” It short-circuits from left to right, so the code won't call a method on `connection` when it is `null`.

**⑨ `close()` releases the resource**

Call it when the application stops:

1. If the manager is already closed, return immediately.
2. Mark the manager as closed.
3. If a connection exists, close it.

Closing the underlying connection also closes its channels. Once the pool is in place, we'll stop lending out channels and clean up the pool before closing the connection.

### 5. How do we verify this step?

Save the file and run:

**Maven → ski-servlet → Lifecycle → compile**

Look for:

```text
BUILD SUCCESS
```

This confirms that the code and its RabbitMQ API references compile correctly.

**There is no `main()` yet, and the Tomcat startup lifecycle doesn't call this code yet, so it won't open a real RabbitMQ connection.** This round verifies compilation. We'll test the actual connection and recovery after deploying RabbitMQ and wiring the manager into the lifecycle.

Next, we'll create `ChannelPool` so we can open multiple channels on one Connection and let request threads borrow and return them.

> The build succeeded, but why are these items highlighted in red?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-dbd5faec-b84f-4598-b8d1-2925127e6986.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-9c81ba6e-9959-4079-bed6-736f37ecba98.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bc71f79d-7c13-4901-b263-4fa8260744c9.png`

**The red highlighting mostly means IntelliJ hasn't resolved the RabbitMQ dependency.** The key message is the first one:

```text
Cannot resolve symbol 'rabbitmq'
```

When IntelliJ can't find the library, it also marks `ConnectionFactory`, `Connection`, and related methods in red. **There's no need to change each method individually.**

Maven recompiled 11 source files and reported `BUILD SUCCESS`, so it found the dependency during compilation. **This looks like IntelliJ's project dependency information is out of sync.**

Try these steps in order.

**1. Reload the Maven projects**

Click **Reload All Maven Projects** at the top of the Maven window on the right. Hover over the refresh icon to see its name.

Wait for dependency loading and indexing to finish, then check whether the red highlighting disappears.

**2. Check the server's dependency list**

Expand:

```text
Maven
└── ski-servlet
    └── Dependencies
```

You should find:

```text
com.rabbitmq:amqp-client:5.36.0
```

Check the dependencies under `ski-servlet`. The `client-part2` project has its own list.

**3. If the dependency is present but the code is still red**

From the macOS menu bar, open **File → Project Structure → Modules**.

Select the module for `server-v2`, which may appear as `ski-servlet`. Open **Dependencies** and look for `amqp-client`.

Hold off on adding the JAR manually. If it appears in the Maven window but is missing here, send a screenshot of this page so we can check how the module is linked.

The other colors in the screenshots mean different things:

| Indicator | Meaning | What to do now |
|---|---|---|
| Red `Cannot resolve...` | The IDE can't find the class or method | Sync the Maven dependencies first |
| Yellow `is never used` | The class or method hasn't been called yet | Expected, since we haven't wired it into startup |
| Green `Typo: skitrak` | The spell checker doesn't recognize the project name | It has no effect on compilation and can be left alone |
| Green file name in the left pane | Usually indicates a newly added file in Git | It doesn't indicate a code error |

**Refresh Maven first, then check the dependency list.** You can leave the Java code and RabbitMQ installation alone.

> Which icon should I click? I don't see Reload.
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-5e498c97-402e-4f32-a860-cd0f555b03f1.png`

**Click the icon you're hovering over.** It's the circular arrow at the far left of the Maven window toolbar, with the tooltip **`Sync All Maven Projects`**.

Your version calls this **Sync**. That's the Maven project synchronization action I called **Reload** earlier.

Click **the circular arrow itself**. The little triangle beside it opens a menu. Wait for synchronization and indexing to finish, then check whether the red highlighting disappears.

> Reloading fixed it. What do the remaining yellow warnings mean?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b5bbc414-c85c-4c7c-9f1e-dd45184470f6.png`

The yellow warnings all say **`is never used`**. That's expected at this stage of development, and they aren't compilation errors.

| Item flagged | Meaning | Where it will be used |
|---|---|---|
| The `RabbitMqConnectionManager` class and constructor | Nothing creates a manager instance yet | Application startup |
| `start()` | Nothing calls it to open a connection yet | Application startup |
| `getConnection()` | Nothing obtains the shared connection yet | Channel pool initialization |
| `isConnected()` | Nothing checks the connection state yet | Later status checks |

We've **defined the component and still need to wire it into the application**. That's why it compiles, but starting Tomcat won't automatically connect to RabbitMQ yet.

The green message at the bottom reads:

```text
Typo: In word 'skitrak'
```

The spell checker doesn't recognize the project name `skitrak`. This has no effect on how the application works.

**You can leave the code as it is for all of these notices.** Next, we'll create `ChannelPool` and start using the shared connection provided by the manager.

> Okay, show me what to do next.

Next, create **`ChannelPool.java`, the channel pool**.

It holds several channels created in advance on a single Connection. Request threads can **borrow a channel, publish a message, and return the channel**, so they don't have to open a new channel for every publish.

This step implements basic pool management. **We'll add rebuilding the entire pool after a disconnection when we integrate it with the connection manager.**

### 1. Create the file

In the left pane in IntelliJ, **right-click `messaging` → New → Java Class → enter `ChannelPool`**.

If Git prompts you, click **Add**.

The package should now contain:

```text
messaging/
├── RabbitMqConfig.java
├── RabbitMqConnectionManager.java
└── ChannelPool.java
```

### 2. Enter the code

```java
package messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class ChannelPool implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(ChannelPool.class.getName());

    // Wait for an available channel for at most five seconds.
    private static final long BORROW_TIMEOUT_SECONDS = 5;

    // Every channel in this pool belongs to the same connection.
    private final Connection connection;
    private final BlockingQueue<Channel> available;
    private final List<Channel> allChannels = new ArrayList<>();

    // Track borrowed channels by object identity.
    private final Set<Channel> borrowed =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean closed;

    // Pre-create channels once instead of creating one per publish.
    public ChannelPool(Connection connection, int poolSize)
            throws IOException {

        this.connection = Objects.requireNonNull(
                connection, "connection must not be null"
        );

        if (poolSize <= 0) {
            throw new IllegalArgumentException(
                    "poolSize must be positive"
            );
        }

        available = new ArrayBlockingQueue<>(poolSize);

        try {
            for (int i = 0; i < poolSize; i++) {
                Channel channel = connection.createChannel();

                if (channel == null) {
                    throw new IOException(
                            "RabbitMQ could not allocate another channel"
                    );
                }

                allChannels.add(channel);

                // Enable publisher confirms before the channel is borrowed.
                channel.confirmSelect();

                available.add(channel);
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // Remove one idle channel so another borrower cannot receive it.
    public Channel borrowChannel()
            throws IOException, InterruptedException, TimeoutException {

        synchronized (this) {
            ensureAvailable();
        }

        // Do not hold the pool lock while waiting for a returned channel.
        Channel channel = available.poll(
                BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS
        );

        synchronized (this) {
            ensureAvailable();

            if (channel == null) {
                throw new TimeoutException(
                        "Timed out waiting for a RabbitMQ channel"
                );
            }

            if (!channel.isOpen()) {
                throw new IOException(
                        "Borrowed channel is closed; pool repair is required"
                );
            }

            borrowed.add(channel);
            return channel;
        }
    }

    // Return a channel only after its publish operation has completed.
    public synchronized void returnChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel must not be null");

        if (!borrowed.remove(channel)) {
            throw new IllegalArgumentException(
                    "Channel was not borrowed from this pool or was already returned"
            );
        }

        if (closed || !connection.isOpen() || !channel.isOpen()) {
            abortQuietly(channel);
            return;
        }

        if (!available.offer(channel)) {
            abortQuietly(channel);

            throw new IllegalStateException(
                    "Channel pool capacity invariant was violated"
            );
        }
    }

    // Check lifecycle state before lending a channel.
    private void ensureAvailable() throws IOException {
        if (closed) {
            throw new IllegalStateException(
                    "Channel pool has already been closed"
            );
        }

        if (!connection.isOpen()) {
            throw new IOException(
                    "RabbitMQ connection is not available"
            );
        }
    }

    // Close all channels owned by this pool, but keep connection ownership separate.
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        available.clear();

        for (Channel channel : allChannels) {
            abortQuietly(channel);
        }
    }

    // Release a channel without preventing cleanup of other channels.
    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Could not abort a RabbitMQ channel");
        }
    }
}
```

### 3. Where it fits in the overall flow

```text
Application starts
    ↓
Connection manager opens one Connection
    ↓
ChannelPool pre-creates several Channels on that Connection
    ↓
Wait for HTTP requests

Request arrives
    ↓
Publisher borrows a Channel
    ↓
Publish the message and wait for the outcome
    ↓
Return the Channel
```

**The pool constructor opens channels over the network, so it needs a working Connection first.** At this point we're only compiling the code. Nothing calls the constructor yet.

### 4. What each section does

**① Fields track idle channels, borrowed channels, and all channels**

| Field | What it holds | Purpose |
|---|---|---|
| `connection` | Shared connection | All channels are opened on this connection |
| `available` | Idle channels | Available for threads to borrow |
| `borrowed` | Channels currently checked out | Prevents returning a channel twice or returning one from a different pool |
| `allChannels` | All channels created by this pool | Lets the pool release all its channels when it closes |
| `closed` | Whether the pool is closed | Prevents borrowing after closure |

These collections can reference the same Channel object. They don't create separate copies of it.

For example, after a thread borrows `C1`:

```text
available: no longer contains C1
borrowed: contains C1
allChannels: still contains C1
```

**② Why does `borrowed` use `IdentityHashMap`?**

```java
Collections.newSetFromMap(new IdentityHashMap<>())
```

This creates a set that checks whether channels are **the same Java object**.

What we want to know is:

> Is the Channel you're returning the exact object this pool lent you?

The channel's name and other data are irrelevant to this check.

`borrowed` is not a concurrent collection, so reads and writes to it are protected by `synchronized`.

**③ The constructor creates channels in advance**

```java
available = new ArrayBlockingQueue<>(poolSize);
```

This creates a thread-safe blocking queue with a fixed capacity. With a `poolSize` of `16`, it can hold at most 16 idle channels.

The loop then calls:

```java
Channel channel = connection.createChannel();
```

It creates Channels on the same Connection and adds them to the pool.

This happens only during **pool initialization**. Normal publishing doesn't open a new channel.

Because `createChannel()` may be unable to allocate another channel, the code checks for a `null` return value.

**④ `confirmSelect()` enables publisher confirms**

```java
channel.confirmSelect();
```

This enables publisher confirms for future messages.

Enabling the feature doesn't confirm that any individual message was published. The publisher will still have to wait for the outcome and check it before deciding whether to return HTTP `201`.

**⑤ Clean up if initialization fails**

```java
catch (IOException | RuntimeException e) {
    close();
    throw e;
}
```

Suppose you plan to create 16 channels and the eighth one fails:

- Clean up the channels already created.
- Rethrow the exception.
- Keep the partially initialized pool from being used by the application.

This closes the Channels held by the pool. The connection manager is responsible for closing the shared Connection.

**⑥ `borrowChannel()` checks out a channel**

It does the following:

1. Checks whether the pool and connection are usable.
2. Waits up to five seconds for an idle channel.
3. Checks the state again in case the application shut down or the connection dropped during the wait.
4. Checks whether the channel is still open.
5. Records the channel as borrowed and returns it to the caller.

The key operation is:

```java
available.poll(5, TimeUnit.SECONDS)
```

This **removes** the channel from the queue, so another thread can't take the same object from the pool.

**⑦ Why must a waiting thread release the lock?**

The code deliberately calls `poll()` outside `synchronized`.

Suppose the pool is empty:

- Thread A is waiting for someone to return a channel.
- Thread B wants to enter `returnChannel()` to return one.

If A kept the pool lock while waiting, B couldn't enter the return method. We therefore hold the lock only while checking and updating shared state and leave it free while waiting for a channel.

**⑧ `returnChannel()` returns a channel**

First, it checks that the channel was borrowed from this pool:

```java
borrowed.remove(channel)
```

The first return successfully removes the record. A second return of the same channel fails, preventing two entries for the same Channel from appearing in the idle queue.

Then:

- If the pool is closed, the connection has dropped, or the channel is closed, clean up the channel without requeuing it.
- If everything is healthy, put it back in the idle queue with `offer()`.

`offer()` doesn't wait indefinitely. Borrowing a channel should free a slot in the queue. A full queue on return means an internal pool invariant has been violated, so the code reports an error.

**The caller must stop using the Channel after returning it.** Another thread may have already borrowed it. The publisher will follow this rule.

**⑨ `close()` and `abortQuietly()` release resources**

`close()`:

1. Marks the pool closed.
2. Clears the idle queue.
3. Closes every channel created by the pool, including channels that are still checked out.

On normal shutdown, stop accepting new requests and let in-flight publishes finish before closing the pool. During failure recovery, let old in-flight operations fail and have the calling code handle those failures.

`abortQuietly()` is a cleanup helper. If closing one channel fails, it logs a warning and keeps cleaning up the other channels.

### 5. What this version covers

This version implements:

- Multiple channels on a single connection.
- Thread-safe borrowing and returning of idle channels.
- A borrow timeout.
- Protection against duplicate returns.
- Cleanup after failed initialization and when the pool closes.

**Replacing failed channels and rebuilding the entire pool after a disconnection still need to be implemented.** This version refuses to use failed channels, which can reduce the pool's capacity. The recovery logic needs to cover these cases to meet the final assignment requirements.

### 6. Verify by compiling

Save the file, then double-click:

**Maven → ski-servlet → Lifecycle → compile**

Confirm that the output shows:

```text
BUILD SUCCESS
```

The application doesn't call `ChannelPool` yet, so yellow `is never used` warnings are expected. Next, we'll add recovery logic so the connection and the pool recover together.

> I've finished this step too. What should I do next?

<details><summary>2 previous messages</summary>

> Next, we'll connect the connection manager and channel pool to implement automatic background recovery. After a connection drops or a channel fails, the program will retire the old resources and rebuild the connection and pool. You'll edit two existing files, and I'll explain what each change does.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/client-libraries/java-api-guide`
>
> </details>
</details>

Next, implement **automatic background recovery** by managing the shared connection and channel pool as a single set of resources.

This round changes two existing files. Create no new files.

| File | Purpose of the change |
|---|---|
| `ChannelPool.java` | Add a method to check whether the pool's connection and channels are still usable |
| `RabbitMqConnectionManager.java` | Check health in the background and rebuild the connection and pool on failure |

We'll start with a simple strategy: **wait five seconds after each background check before starting the next one. If a check detects a failure, rebuild the entire set of resources.** This gives us clear recovery logic that we can tune after testing.

### Step 1: Add a health check to `ChannelPool`

Open `ChannelPool.java`.

Add this method before `close()`. Put it inside the class braces, outside any other method:

```java
    // Check every owned channel, including channels currently borrowed.
    public synchronized boolean isHealthy() {
        if (closed || !connection.isOpen()) {
            return false;
        }

        for (Channel channel : allChannels) {
            if (!channel.isOpen()) {
                return false;
            }
        }

        return true;
    }
```

It checks:

1. Whether the pool has already been closed.
2. Whether the underlying connection is still open.
3. Whether every channel the pool created is still open.

If any check fails, it returns `false`.

**Checking whether the idle queue is empty is insufficient.** The pool can be perfectly healthy when all its channels are checked out and the queue is empty.

`isHealthy()` checks the current state. The network could drop immediately afterward, so publishing still needs to handle exceptions.

### Step 2: Replace the connection manager

Open:

```text
messaging/RabbitMqConnectionManager.java
```

**Replace the entire file with the code below.** The new code takes the place of the old class.

```java
package messaging;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public final class RabbitMqConnectionManager implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(RabbitMqConnectionManager.class.getName());

    private static final long CHECK_DELAY_SECONDS = 5;

    private final ConnectionFactory factory;
    private final int poolSize;

    // Use one background worker to prevent concurrent recovery attempts.
    private final ScheduledExecutorService recoveryExecutor =
            Executors.newSingleThreadScheduledExecutor();

    // Publish the connection and its pool together as one resource group.
    private volatile Resources current;
    private volatile boolean closed;
    private boolean started;

    // Configure connection settings without opening a connection.
    public RabbitMqConnectionManager(RabbitMqConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        poolSize = config.getChannelPoolSize();

        factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());

        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        factory.setRequestedHeartbeat(30);

        // Let this manager coordinate connection and pool recovery.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
    }

    // Start background initialization and subsequent recovery checks.
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Connection manager has already been closed"
            );
        }

        if (started) {
            return;
        }

        started = true;

        recoveryExecutor.scheduleWithFixedDelay(
                this::maintainResources,
                0,
                CHECK_DELAY_SECONDS,
                TimeUnit.SECONDS
        );
    }

    // Return one specific pool so channels can be returned to that same pool.
    public ChannelPool getChannelPool() {
        Resources snapshot = current;

        if (closed || snapshot == null || !snapshot.pool.isHealthy()) {
            throw new IllegalStateException(
                    "RabbitMQ resources are not ready"
            );
        }

        return snapshot.pool;
    }

    // Report readiness of both the connection and its channel pool.
    public boolean isConnected() {
        Resources snapshot = current;

        return !closed
                && snapshot != null
                && snapshot.pool.isHealthy();
    }

    // Keep network operations outside the manager's lifecycle lock.
    private void maintainResources() {
        if (closed) {
            return;
        }

        Resources snapshot = current;

        if (snapshot != null && snapshot.pool.isHealthy()) {
            return;
        }

        synchronized (this) {
            if (closed) {
                return;
            }

            current = null;
        }

        if (snapshot != null) {
            snapshot.close();
        }

        Connection candidateConnection = null;
        ChannelPool candidatePool = null;
        boolean installed = false;

        try {
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);

            Resources replacement =
                    new Resources(candidateConnection, candidatePool);

            synchronized (this) {
                if (!closed) {
                    current = replacement;
                    installed = true;
                }
            }

            if (installed) {
                LOGGER.info("RabbitMQ connection and channel pool are ready");
            }

        } catch (IOException | TimeoutException | RuntimeException e) {
            if (!closed) {
                LOGGER.warning(
                        "RabbitMQ initialization or recovery failed: "
                                + e.getClass().getSimpleName()
                                + "; another attempt will follow"
                );
            }
        } finally {
            // Clean up partial initialization or a shutdown race.
            if (!installed) {
                if (candidatePool != null) {
                    candidatePool.close();
                }

                abortConnection(candidateConnection);
            }
        }
    }

    // Stop future recovery and detach the current resource group.
    @Override
    public void close() {
        Resources detached;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
            detached = current;
            current = null;
        }

        recoveryExecutor.shutdownNow();

        if (detached != null) {
            detached.close();
        }
    }

    // Release a connection even when normal communication is unavailable.
    private static void abortConnection(Connection connection) {
        if (connection == null) {
            return;
        }

        try {
            connection.abort();
        } catch (RuntimeException e) {
            LOGGER.warning("Could not abort a RabbitMQ connection");
        }
    }

    // Keep each connection paired with the pool created from it.
    private static final class Resources {
        private final Connection connection;
        private final ChannelPool pool;

        private Resources(Connection connection, ChannelPool pool) {
            this.connection = connection;
            this.pool = pool;
        }

        private void close() {
            pool.close();
            abortConnection(connection);
        }
    }
}
```

### Step 3: Understand the new code

**① Why group the Connection and ChannelPool together?**

The nested class at the end:

```java
private static final class Resources
```

stores the resources that belong together:

```text
One Connection
    +
The ChannelPool created on that Connection
```

During recovery, prepare the entire replacement before making it available to other threads:

```text
Open a new Connection
    ↓
Build a new ChannelPool on the new Connection
    ↓
Confirm that both were created successfully
    ↓
Make the new set of resources available
```

This prevents the application from using a new connection alongside a pool of channels from the old one.

**② What are `current`, `closed`, and `started`?**

| Field | Meaning |
|---|---|
| `current` | The current set of resources available to application code |
| `closed` | Whether the application has told the manager to stop |
| `started` | Whether background monitoring has already started, to prevent starting it twice |

`volatile` makes field updates by one thread visible to other threads.

Compound operations still need coordination. The code uses `synchronized` to protect key state when starting, stopping, or installing new resources.

**③ How has `start()` changed?**

The previous version opened the connection on the calling thread.

**Now it starts a background task and returns immediately. The background task opens the first connection too.**

```java
scheduleWithFixedDelay(
        this::maintainResources,
        0,
        5,
        TimeUnit.SECONDS
);
```

This means:

- Schedule the first run with no initial delay.
- Run `maintainResources()`.
- Wait five seconds after that run finishes.
- Run it again.

If opening a connection takes a few seconds, that time is added to the interval between the starts of successive checks. **The checks do not run on a fixed five-second schedule.**

The executor has one worker thread, so dozens of HTTP requests won't attempt to reconnect at the same time.

**④ `maintainResources()` contains the recovery logic**

It does four things:

1. **If the manager has stopped, return.**
2. **If the current resources are healthy, reuse them without opening a new connection.**
3. **If the resources have failed, remove them and close the old pool and connection.**
4. **Try to open a new connection and create a new pool. Make them available only after both succeed.**

If an attempt fails, log the type of error and clean up any resources created so far. The next background check will try again.

This version uses **fixed-delay retries**. We can add the exponential backoff discussed earlier if we need it later.

**⑤ Why have `candidateConnection`, `candidatePool`, and `installed`?**

A candidate is a resource under preparation and isn't available to application code yet.

For example:

- If the connection opens but pool initialization fails, close the connection you just opened.
- If both resources are ready but the application has just stopped, clean them up without installing them.
- If the complete set is installed successfully, the manager takes responsibility for it and `finally` leaves it open.

`installed` tells these cases apart.

**⑥ Why do network operations run outside `synchronized`?**

Opening connections or creating channels may involve waiting on the network.

Holding the manager's lock during that wait could block another thread trying to stop the application. The code therefore takes the lock briefly when it changes lifecycle state.

If the manager closes while a connection is being opened, the code checks `closed` again once the new resources are ready. This prevents them from being activated after shutdown.

**⑦ Why does `getChannelPool()` return a specific pool?**

The publisher will need to follow this rule:

```text
Get pool A
    ↓
Borrow a channel from pool A
    ↓
Publish the message
    ↓
Return the channel to pool A
```

Even if recovery installs pool B in the meantime, a channel from A must go back to A.

The publisher therefore saves a reference to the pool and uses that same reference to borrow and return the channel. This version removes the old `getConnection()` method so the publisher can't bypass the pool and open channels itself.

**⑧ Why rebuild the whole pool when one channel fails?**

This is a simplification for this stage:

- One recovery path is easier to understand and verify.
- Channels from old and new connections stay separate.
- Any failed channel can be detected and recovered.

The cost is that **even one failed channel causes the others to be closed, and in-flight publishes may fail**. The publisher must report those failures accurately and use stable event IDs to support retries.

RabbitMQ distinguishes connection-level failures from channel-level failures. This implementation handles both by rebuilding the same set of resources. [Official client guide](https://www.rabbitmq.com/client-libraries/java-api-guide)

**⑨ `close()` prevents recovery after an intentional shutdown**

It sets `closed`, stops the background executor, and then cleans up the current resources.

If a connection attempt is still in progress, cleanup happens after it succeeds or fails. Once we integrate with Tomcat's lifecycle, the application shutdown logic will call this method.

### Step 4: Verify by compiling

Save both files and run:

**Maven → ski-servlet → Lifecycle → compile**

The expected result is:

```text
BUILD SUCCESS
```

Nothing calls the manager's `start()` yet, so the application won't actually connect to RabbitMQ. **This step verifies compilation. To confirm automatic recovery, we'll test an actual disconnection after deploying RabbitMQ.**

Next, create the queue topology declaration component. It will define `skitrak.exchange`, the 10 ski resort queues, and the binding rules, and we'll wire it into initialization and recovery.

> 1. By the way, I found the records for all 40 lifts. Here they are:
>
> {\
> &#x20; "description": "SkiTrak sample lift->vertical(ft) table (liftID 1-40)",\
> &#x20; "lifts": {\
> &#x20;   "1": 167,\
> &#x20;   "2": 74,\
> &#x20;   "3": 344,\
> &#x20;   "4": 273,\
> &#x20;   "5": 383,\
> &#x20;   "6": 397,\
> &#x20;   "7": 104,\
> &#x20;   "8": 88,\
> &#x20;   "9": 288,\
> &#x20;   "10": 183,\
> &#x20;   "11": 204,\
> &#x20;   "12": 214,\
> &#x20;   "13": 222,\
> &#x20;   "14": 104,\
> &#x20;   "15": 364,\
> &#x20;   "16": 357,\
> &#x20;   "17": 213,\
> &#x20;   "18": 336,\
> &#x20;   "19": 173,\
> &#x20;   "20": 223,\
> &#x20;   "21": 267,\
> &#x20;   "22": 315,\
> &#x20;   "23": 250,\
> &#x20;   "24": 200,\
> &#x20;   "25": 368,\
> &#x20;   "26": 181,\
> &#x20;   "27": 223,\
> &#x20;   "28": 88,\
> &#x20;   "29": 138,\
> &#x20;   "30": 99,\
> &#x20;   "31": 262,\
> &#x20;   "32": 213,\
> &#x20;   "33": 70,\
> &#x20;   "34": 125,\
> &#x20;   "35": 340,\
> &#x20;   "36": 63,\
> &#x20;   "37": 296,\
> &#x20;   "38": 103,\
> &#x20;   "39": 374,\
> &#x20;   "40": 269\
> &#x20; }\
> }
>
> 2. I've saved both files and successfully compiled them. What's next?

<details><summary>2 previous messages</summary>

> Now that we have the table, we can implement the lift vertical lookup before configuring the queue topology. I'll walk you through saving the data as a resource file and creating a class that reads it once at initialization and uses the loaded data for later lookups. Remove the `&#x20;` entities and trailing backslashes from the pasted text when you save it. They're formatting escapes.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/JsonParser.html`
>
> </details>
</details>

Both files compiled, so the current code builds. **Now that you've found the vertical-drop table, let's integrate it into the project before configuring the queue topology.**

Create two files:

| File | Purpose |
|---|---|
| `src/main/resources/lift-vertical.json` | Stores the 40 vertical-drop values you found |
| `src/main/java/model/LiftVerticalLookup.java` | Loads and validates the data and looks up vertical drop by lift ID |

This keeps **the data** separate from **the lookup logic**, so you don't have to hard-code all 40 numbers in the business logic.

### 1. Create the data file

In IntelliJ's left pane, find:

```text
server-v2 → src → main → resources
```

If `resources` is missing, right-click **main → New → Directory** and name the directory `resources`.

Then right-click **resources → New → File** and enter:

```text
lift-vertical.json
```

Click **Add** if Git prompts you.

Paste the following. The `&#x20;` entities and trailing backslashes from the original paste have been removed, and the numbers are the same:

```json
{
  "description": "SkiTrak sample lift->vertical(ft) table (liftID 1-40)",
  "lifts": {
    "1": 167,
    "2": 74,
    "3": 344,
    "4": 273,
    "5": 383,
    "6": 397,
    "7": 104,
    "8": 88,
    "9": 288,
    "10": 183,
    "11": 204,
    "12": 214,
    "13": 222,
    "14": 104,
    "15": 364,
    "16": 357,
    "17": 213,
    "18": 336,
    "19": 173,
    "20": 223,
    "21": 267,
    "22": 315,
    "23": 250,
    "24": 200,
    "25": 368,
    "26": 181,
    "27": 223,
    "28": 88,
    "29": 138,
    "30": 99,
    "31": 262,
    "32": 213,
    "33": 70,
    "34": 125,
    "35": 340,
    "36": 63,
    "37": 296,
    "38": 103,
    "39": 374,
    "40": 269
  }
}
```

In this file:

- `description` explains what the data is for and isn't used in calculations.
- `lifts` maps lift IDs to vertical drops.
- `"1": 167` means lift 1 contributes 167 feet per ride. It is **a per-ride value**.

`resources` is Maven's standard resource directory. The build copies the file to a location the application can load at runtime and includes it in the WAR. There is no need to specify an absolute path on your Mac.

### 2. Create the lookup class

Right-click **server-v2 → src → main → java → model**.

Choose **New → Java Class** and enter:

```text
LiftVerticalLookup
```

This is a read-only lookup utility, so we're putting it alongside the existing data models in the `model` package. It doesn't handle RabbitMQ communication.

Enter:

```java
package model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class LiftVerticalLookup {

    private static final String RESOURCE_PATH = "/lift-vertical.json";
    private static final int LIFT_COUNT = 40;

    // Load the table once when this class is initialized.
    private static final int[] VERTICAL_BY_LIFT = loadTable();

    // This utility class does not need instances.
    private LiftVerticalLookup() {
    }

    // Return the vertical feet for one valid lift ID.
    public static int getVertical(int liftID) {
        if (liftID < 1 || liftID > LIFT_COUNT) {
            throw new IllegalArgumentException(
                    "liftID must be between 1 and 40"
            );
        }

        return VERTICAL_BY_LIFT[liftID];
    }

    // Read and validate the bundled table before making it available.
    private static int[] loadTable() {
        InputStream stream =
                LiftVerticalLookup.class.getResourceAsStream(RESOURCE_PATH);

        if (stream == null) {
            throw new IllegalStateException(
                    "Missing resource: " + RESOURCE_PATH
            );
        }

        try (Reader reader = new InputStreamReader(
                stream, StandardCharsets.UTF_8)) {

            JsonObject root =
                    JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject lifts = root.getAsJsonObject("lifts");

            if (lifts == null || lifts.size() != LIFT_COUNT) {
                throw new IllegalStateException(
                        "The lift table must contain exactly 40 lifts"
                );
            }

            int[] table = new int[LIFT_COUNT + 1];

            for (int liftID = 1; liftID <= LIFT_COUNT; liftID++) {
                JsonElement value = lifts.get(Integer.toString(liftID));

                if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()) {

                    throw new IllegalStateException(
                            "Missing or nonnumeric vertical for lift " + liftID
                    );
                }

                int vertical = value.getAsBigDecimal().intValueExact();

                if (vertical < 60 || vertical > 400) {
                    throw new IllegalStateException(
                            "Vertical must be between 60 and 400 for lift "
                                    + liftID
                    );
                }

                table[liftID] = vertical;
            }

            return table;

        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to load a valid lift vertical table",
                    e
            );
        }
    }
}
```

### 3. What each section does

**① Three static fields store the resource location, lift count, and in-memory table**

```java
private static final String RESOURCE_PATH = "/lift-vertical.json";
private static final int LIFT_COUNT = 40;
private static final int[] VERTICAL_BY_LIFT = loadTable();
```

- `RESOURCE_PATH` finds the file at the application's classpath root. It does not refer to the root of your Mac's disk.
- `LIFT_COUNT` defines the lift IDs as 1–40.
- `VERTICAL_BY_LIFT` stores the JSON data in an in-memory array.

`loadTable()` runs once when the class is first initialized. Normal lookups don't reread the file or parse JSON again. After redeployment, the new application loads its own table.

**② The private constructor prevents unnecessary instantiation**

```java
private LiftVerticalLookup() {
}
```

The class only provides a read-only lookup. Call:

```java
LiftVerticalLookup.getVertical(1)
```

to get `167` without calling `new LiftVerticalLookup()`.

**③ `getVertical()` validates the ID, then looks up the value**

It rejects IDs outside 1–40 and returns the value at the corresponding array index.

| Call | Result |
|---|---:|
| `getVertical(1)` | `167` |
| `getVertical(24)` | `200` |
| `getVertical(40)` | `269` |
| `getVertical(0)` | Throws an invalid argument exception |

The array has length `41` so the lift ID can be used directly as its index. Index `0` is unused, avoiding repeated `liftID - 1` calculations.

**④ `getResourceAsStream()` reads the packaged resource**

```java
LiftVerticalLookup.class.getResourceAsStream(RESOURCE_PATH)
```

It opens the file from the application's resources. Once deployed to EC2, the code can still read the file as long as it's packaged in the WAR. It doesn't depend on your local Downloads directory.

A missing file causes an explicit error. The code never silently substitutes `0` or a random drop.

**⑤ `try (...)` automatically closes the file resources**

```java
try (Reader reader = new InputStreamReader(
        stream, StandardCharsets.UTF_8)) {
```

This is called **try-with-resources**. It closes the Reader and underlying stream when the block exits, whether reading succeeds or throws an exception.

`UTF_8` specifies the text encoding.

**⑥ Gson converts the JSON into a structure we can query**

```java
JsonObject root =
        JsonParser.parseReader(reader).getAsJsonObject();

JsonObject lifts = root.getAsJsonObject("lifts");
```

The code reads the full JSON document, then retrieves its `lifts` object. It uses the project's existing Gson dependency. [Gson `parseReader` documentation](https://javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/JsonParser.html)

**⑦ Validation prevents a bad table from corrupting cumulative totals**

At load time, the code checks that:

- The `lifts` object exists.
- It contains 40 entries.
- IDs 1–40 are all present.
- Every value is a JSON number.
- Each value can be represented exactly as an integer.
- Each value is between 60 and 400 feet.

This line:

```java
value.getAsBigDecimal().intValueExact()
```

prevents `167.5` from being silently truncated to `167`. It throws an error if the value can't be converted exactly.

**⑧ The array is populated at load time and treated as read-only afterward**

```java
table[liftID] = vertical;
```

After loading succeeds, the class keeps the array private and exposes neither the array itself nor an update method. Later request threads only read it.

`final` alone doesn't make array elements immutable. The read-only behavior comes from **keeping the array private, leaving it untouched after initialization, and never handing it to external code**.

### 4. Verify this step

Save both files and run:

**Maven → ski-servlet → Lifecycle → compile**

Check two things:

1. The output shows `BUILD SUCCESS`.
2. In the left pane, expand this path and confirm the resource was copied:

```text
server-v2
└── target
    └── classes
        └── lift-vertical.json
```

**A successful build and resource copy don't mean the loading code has run yet.** Next, we'll run a small standalone check of all 40 values and invalid IDs without RabbitMQ. Once we confirm that the table loads correctly, we'll continue configuring the queue topology.

> It compiled successfully. What should I do next?

Next, **run a check of the vertical-drop table** to confirm that the program can find the JSON file, read all 40 values, and reject invalid lift IDs. You can run this check without starting RabbitMQ or Tomcat.

Add one file, **`LiftVerticalLookupCheck.java`**. It verifies the lookup class and belongs in the test source directory, separate from the production server code.

### 1. Create the check file

In the left pane, find:

```text
server-v2 → src → test → java
```

If these directories don't exist, create `test` under `src`, then create `java` under `test`.

Right-click **`java` in the test directory → New → Package** and enter:

```text
model
```

Then right-click the test package and choose **New → Java Class → `LiftVerticalLookupCheck`**.

The full path is:

```text
server-v2/src/test/java/model/LiftVerticalLookupCheck.java
```

The check is in the same `model` package as the production class, under a different source directory:

```text
src/
├── main/
│   ├── java/model/LiftVerticalLookup.java
│   └── resources/lift-vertical.json
└── test/
    └── java/model/LiftVerticalLookupCheck.java
```

### 2. Enter the code

```java
package model;

public final class LiftVerticalLookupCheck {

    public static void main(String[] args) {

        // Use the supplied table as the expected result for every lift.
        int[] expected = {
                167, 74, 344, 273, 383, 397, 104, 88, 288, 183,
                204, 214, 222, 104, 364, 357, 213, 336, 173, 223,
                267, 315, 250, 200, 368, 181, 223, 88, 138, 99,
                262, 213, 70, 125, 340, 63, 296, 103, 374, 269
        };

        // Verify all 40 mappings, including the first and last lift.
        for (int liftID = 1; liftID <= expected.length; liftID++) {
            int actual = LiftVerticalLookup.getVertical(liftID);
            int expectedVertical = expected[liftID - 1];

            if (actual != expectedVertical) {
                throw new AssertionError(
                        "Lift " + liftID
                                + ": expected " + expectedVertical
                                + ", but got " + actual
                );
            }
        }

        System.out.println("PASS: all 40 lift mappings are correct.");

        // Verify that IDs outside the supported range are rejected.
        expectInvalidLift(-1);
        expectInvalidLift(0);
        expectInvalidLift(41);
        expectInvalidLift(Integer.MAX_VALUE);

        System.out.println("PASS: invalid lift IDs are rejected.");

        // Verify accumulation using two rides by the same skier.
        int total = LiftVerticalLookup.getVertical(1)
                + LiftVerticalLookup.getVertical(2);

        if (total != 241) {
            throw new AssertionError(
                    "Expected a total of 241 feet, but got " + total
            );
        }

        System.out.println("PASS: lifts 1 and 2 total 241 feet.");
        System.out.println("All lift vertical lookup checks passed.");
    }

    // Fail the check if an invalid ID is accepted.
    private static void expectInvalidLift(int liftID) {
        try {
            LiftVerticalLookup.getVertical(liftID);
        } catch (IllegalArgumentException expected) {
            return;
        }

        throw new AssertionError(
                "Expected rejection of invalid lift ID: " + liftID
        );
    }
}
```

### 3. What each section does

**① `main()` provides a standalone entry point**

The test class has a `main()` method, so you can run it directly from IntelliJ without starting the entire server.

The first call to `LiftVerticalLookup.getVertical()` triggers loading of the table. If the resource is missing or its contents are invalid, the program reports an error immediately.

**② `expected` holds the values to check against**

It stores the data you supplied in lift ID order, from 1 through 40.

The production lookup class reads the JSON file. This check compares every returned value against the expected data to catch a number copied incorrectly or a value associated with the wrong lift ID.

The arrays use different indexing:

- In the lookup class, the index is the `liftID`.
- In `expected`, lift 1's value is stored at index `0`.

So the expected value is accessed with:

```java
expected[liftID - 1]
```

**③ The loop checks all 40 mappings**

If the actual and expected values differ, it throws an `AssertionError` that identifies the lift and shows both values.

The code throws the error explicitly, so the check works regardless of whether Java assertions are enabled.

**④ `expectInvalidLift()` checks invalid IDs**

We deliberately pass `-1`, `0`, `41`, and the maximum integer.

- If it receives the expected `IllegalArgumentException`, the lookup correctly rejected the ID and the check passes.
- If no exception is thrown, the lookup incorrectly accepted the invalid ID and the check fails.

Other exception types propagate and cause the check to fail.

**⑤ Adding two rides demonstrates how to use the vertical-drop values**

Lift 1 contributes `167` feet and lift 2 contributes `74` feet. Together:

```text
167 + 74 = 241 英尺
```

This verifies that lookup results can be added to a running total. Testing the consumer's actual aggregation logic comes later.

### 4. Run the check in IntelliJ

Save the file, click the green triangle beside `main()`, and choose:

```text
Run 'LiftVerticalLookupCheck.main()'
```

Expected output:

```text
PASS: all 40 lift mappings are correct.
PASS: invalid lift IDs are rejected.
PASS: lifts 1 and 2 total 241 feet.
All lift vertical lookup checks passed.
```

The process should finish with:

```text
Process finished with exit code 0
```

Run this check manually. Placing it under `src/test/java` does not make Maven's `test` goal execute it automatically.

If right-clicking the test directory doesn't show **Java Class**, click **Sync All Maven Projects** in the Maven window so IntelliJ recognizes the standard test source directory.

Once the output matches, we've verified the table. Next, we'll define the RabbitMQ exchange, ten queues, and binding rules.

> Here's what I got after running it. What should I do next, and how far along am I?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-a4cff647-9060-4e14-9bc6-564b4333725f.png`

<details><summary>2 previous messages</summary>

> The screenshot shows that all vertical-drop table checks passed, with exit code 0. Next, we'll define the RabbitMQ exchange, queues, and bindings and wire them into connection initialization and recovery. I'll also distinguish what has compiled from what has been verified at runtime so I can give you a practical progress estimate.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/docs/maxlength`
>
> </details>
</details>

The screenshot shows the expected results: **all 40 mappings, the invalid ID checks, and the cumulative total example passed, and the exit code was `0`.** This is the first new feature we've verified by running it beyond compiling the code.

### Your progress so far

Based on the overall implementation, deployment, testing, and reporting effort, **you're roughly 15%–20% done**. That's an estimate of work completed, independent of grading.

| Component | Current state |
|---|---|
| Project copy and Maven setup | Complete |
| Swipe message model | Created and compiled |
| Official lift vertical-drop table and lookup | **Verified at runtime** |
| RabbitMQ configuration, connection manager, channel pool, and recovery | Compiled and still awaiting verification against a running RabbitMQ broker |
| Publishing swipe events | Yet to be implemented |
| Consumer, deduplication, cumulative vertical feet, and real leaderboard | Yet to be implemented |
| Broadcast backplane and client integration | Yet to be implemented |
| Multiple AWS instances, ALB, load testing, and report | Pending |

You're currently **building the producer infrastructure**. Next, define the exchange and queues that will run in RabbitMQ.

### 1. What will we create in this step?

Create:

```text
server-v2/src/main/java/messaging/RabbitMqTopology.java
```

Also add one call in `RabbitMqConnectionManager.java`.

**Topology** here means how the exchanges, queues, and bindings are arranged:

```text
skitrak.exchange [topic]
    ├─ resort.1 → resort.1 queue
    ├─ resort.2 → resort.2 queue
    ├─ ...
    └─ resort.10 → resort.10 queue
```

For example, a message with routing key `resort.3` goes to the queue bound with `resort.3`. We chose to use the same text for the queue name and routing key. The binding operation establishes the relationship. [RabbitMQ topic exchange guide](https://www.rabbitmq.com/tutorials/tutorial-five-java)

### 2. Choose the queue settings first

The assignment requires a TTL and a queue limit and leaves the values up to us. Start with:

| Setting | Initial value | Meaning |
|---|---:|---|
| Message TTL | 24 hours | A swipe message expires if it waits in the queue longer than this |
| Queue capacity per resort | 100,000 messages ready for delivery | Initial capacity guard, adjustable after testing |
| Overflow strategy | `reject-publish` | Reject new publishes when the queue is full |
| Queue durability | Enabled | Queue definitions can survive a broker restart |

**The 100,000-message capacity limit is separate from the target queue depth.** The performance goal is still to keep the depth below 1,000. Capping the queue at 1,000 and dropping excess messages would defeat the purpose of the goal.

Because messages can expire under the TTL, add a **queue to collect expired messages** for investigation. A message in that queue has yet to be aggregated. Verify that no swipe events expire during the actual load test.

The default overflow behavior may remove older messages. We explicitly use `reject-publish` and will check the confirmation result in the publisher later. [Queue length limits](https://www.rabbitmq.com/docs/maxlength) · [TTL](https://www.rabbitmq.com/docs/ttl)

### 3. Create `RabbitMqTopology.java`

Right-click **`messaging` → New → Java Class** and enter:

```text
RabbitMqTopology
```

Enter this code:

```java
package messaging;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public final class RabbitMqTopology {

    public static final String SWIPE_EXCHANGE = "skitrak.exchange";

    private static final int RESORT_COUNT = 10;

    private static final int MESSAGE_TTL_MS = 86_400_000;
    private static final int MAX_QUEUE_LENGTH = 100_000;

    private static final String DEAD_LETTER_EXCHANGE = "skitrak.dlx";
    private static final String EXPIRED_QUEUE = "skitrak.expired";
    private static final String EXPIRED_ROUTING_KEY = "expired";

    // This utility class does not need instances.
    private RabbitMqTopology() {
    }

    // Declare topology during initialization and connection recovery.
    public static void declare(Connection connection)
            throws IOException, TimeoutException {

        Channel created = connection.createChannel();

        if (created == null) {
            throw new IOException(
                    "Could not allocate a topology initialization channel"
            );
        }

        // Use a dedicated setup channel instead of a borrowed publishing channel.
        try (Channel channel = created) {

            channel.exchangeDeclare(
                    SWIPE_EXCHANGE,
                    BuiltinExchangeType.TOPIC,
                    true
            );

            // Keep expired swipe messages separate from normal processing.
            channel.exchangeDeclare(
                    DEAD_LETTER_EXCHANGE,
                    BuiltinExchangeType.DIRECT,
                    true
            );

            channel.queueDeclare(
                    EXPIRED_QUEUE,
                    true,
                    false,
                    false,
                    null
            );

            channel.queueBind(
                    EXPIRED_QUEUE,
                    DEAD_LETTER_EXCHANGE,
                    EXPIRED_ROUTING_KEY
            );

            Map<String, Object> arguments = new HashMap<>();
            arguments.put("x-message-ttl", MESSAGE_TTL_MS);
            arguments.put("x-max-length", MAX_QUEUE_LENGTH);
            arguments.put("x-overflow", "reject-publish");
            arguments.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
            arguments.put("x-dead-letter-routing-key", EXPIRED_ROUTING_KEY);

            // Create one queue and one exact binding per resort.
            for (int resortID = 1; resortID <= RESORT_COUNT; resortID++) {
                String queueName = routingKey(resortID);

                channel.queueDeclare(
                        queueName,
                        true,
                        false,
                        false,
                        arguments
                );

                channel.queueBind(
                        queueName,
                        SWIPE_EXCHANGE,
                        routingKey(resortID)
                );
            }
        }
    }

    // Use the same routing key convention throughout the application.
    public static String routingKey(int resortID) {
        if (resortID < 1 || resortID > RESORT_COUNT) {
            throw new IllegalArgumentException(
                    "resortID must be between 1 and 10"
            );
        }

        return "resort." + resortID;
    }
}
```

### 4. What each section does

**① Constants keep names and initial settings consistent**

```java
public static final String SWIPE_EXCHANGE = "skitrak.exchange";
```

The publisher will use this name later, so it is `public`. Constants used only within the topology class stay `private`.

`86_400_000` is in milliseconds, which is 24 hours. The underscores make the number easier to read and don't affect its value.

**② `declare()` declares resources in RabbitMQ**

This method talks to RabbitMQ at runtime to create exchanges, queues, and bindings.

An existing resource can be declared again if the name and properties match. **If the properties differ, RabbitMQ raises an error and keeps the existing configuration.** Keep that in mind if you change the TTL or other settings later.

**③ A dedicated initialization Channel**

```java
Channel created = connection.createChannel();
```

We use a temporary channel to initialize the topology and close it afterward.

**This channel is for initialization or failure recovery.** In normal operation, the publisher still borrows and returns a pooled channel for each message.

`try (Channel channel = created)` closes the temporary channel when the block exits and leaves the Connection open.

**④ The main exchange**

```java
channel.exchangeDeclare(
        SWIPE_EXCHANGE,
        BuiltinExchangeType.TOPIC,
        true
);
```

The three arguments specify:

1. The name.
2. The topic exchange type.
3. A durable exchange definition.

A durable exchange definition and persistent messages are separate settings. The publisher still needs to mark messages as persistent and check publisher confirms.

**⑤ Collecting expired messages**

The declarations establish this path:

```text
skitrak.dlx [direct]
    ↓ binding key: expired
skitrak.expired queue
```

When a message expires in a swipe queue, this dead-letter exchange can route it to the collection queue.

There is no consumer that automatically processes those messages again, and no unlimited retries are configured. **The queue helps you detect problems. Check how many messages end up there and investigate why they expired.**

**⑥ `arguments` holds optional queue settings**

These keys are defined by RabbitMQ:

```java
arguments.put("x-message-ttl", MESSAGE_TTL_MS);
```

This sets the message TTL.

```java
arguments.put("x-max-length", MAX_QUEUE_LENGTH);
arguments.put("x-overflow", "reject-publish");
```

These set the capacity limit and what happens when the queue is full.

The final two arguments identify the dead-letter exchange and the routing key to use when forwarding messages to it.

The values are constants to keep the first version consistent. If we need to tune them more flexibly later, we can move them into configuration or a RabbitMQ policy.

**⑦ A loop creates the ten ski resort queues**

```java
channel.queueDeclare(
        queueName,
        true,
        false,
        false,
        arguments
);
```

The five arguments are:

| Argument | Purpose |
|---|---|
| `queueName` | Queue name |
| `true` | Makes the queue definition durable |
| First `false` | Makes the queue non-exclusive so other connections can use it |
| Second `false` | Keeps the queue from being automatically deleted when the consumer leaves |
| `arguments` | TTL, capacity, and dead-letter settings |

Then `queueBind()` connects each queue to the main exchange.

**⑧ `routingKey()` centralizes how routing keys are generated**

For example:

```java
RabbitMqTopology.routingKey(3)
```

returns:

```text
resort.3
```

The publisher can reuse this method instead of assembling the string in multiple places. The method also rejects resort IDs outside 1–10.

### 5. Wire it into connection initialization and recovery

Open `RabbitMqConnectionManager.java` and find:

```java
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);
```

Change it to:

```java
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            // Ensure exchanges, queues, and bindings exist before becoming ready.
            RabbitMqTopology.declare(candidateConnection);

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);
```

**The only additions here are one call and one comment.** Both classes are in the `messaging` package, so you don't need another `import`.

The initialization sequence is now:

```text
Open a Connection
    ↓
Declare exchanges, queues, and bindings
    ↓
Create the ChannelPool
    ↓
Make the complete set of resources available
```

If topology declaration fails, the connection never becomes ready for use. The existing exception cleanup and background retries handle the failure.

Recovery follows the same path, so it rebuilds the connection and pool and verifies that the required topology exists.

### 6. Verification and the next milestone

Save the files and run:

**Maven → ski-servlet → Lifecycle → compile**

`BUILD SUCCESS` confirms that the code for this step is ready.

**The RabbitMQ queues haven't been created yet.** The manager hasn't started or connected to a running broker. Next, deploy a dedicated RabbitMQ EC2 instance, set up the account and network access, and verify the connection, topology, and recovery against the running broker.

The next major milestone is:

> **The server connects to RabbitMQ, all ten ski resort queues are visible, and the connection recovers automatically after a disconnection.**

Once you reach that milestone, wire up `SwipePublisher` and `SkierServlet` so HTTP swipe requests actually reach the queues.

> This step shows BUILD SUCCESS. What should I do next?

<details><summary>2 previous messages</summary>

> Next, deploy a dedicated RabbitMQ EC2 instance so you can actually verify the connection, queue creation, and recovery after disconnection. In this round, we'll check the existing ingest server's network settings before creating the RabbitMQ instance so we select the correct Region and VPC.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html`
>
> </details>
</details>

Next is the **AWS deployment stage: prepare a dedicated RabbitMQ EC2 instance**. The code builds, and the next step is to verify that it can connect to a running RabbitMQ broker, create the queues, and recover after a disconnection.

**This round covers the existing server's network settings. Leave the Java files as they are.**

### 1. Open the existing EC2 instance

Sign in to AWS and go to **EC2 → Instances**.

Find the server that ran Tomcat for Assignment 1. You can view its settings even if it's stopped, so you can leave it stopped for now.

Select the instance and record:

| Information | Where to find it | Why you need it |
|---|---|---|
| Region | Upper-right corner of the console | Create RabbitMQ in the same Region |
| VPC ID | Networking tab below | Put RabbitMQ in the same VPC |
| Subnet ID | Networking tab | Confirm the subnet and network access |
| Security group ID | Security tab | Allow the ingest server to reach RabbitMQ |
| Key pair name | Details tab | Identify the key used for SSH |

In AWS, the VPC is the virtual network where the instance runs. The security group controls which traffic can enter. [AWS EC2 guide](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html)

### 2. Why check this first?

We'll use this communication path:

```text
Ingest server EC2
    │ Uses the RabbitMQ private IP
    │ TCP 5672
    ▼
RabbitMQ EC2
```

Putting both instances in the same VPC lets them communicate over private IPs. The RabbitMQ security group can allow access to `5672` only from the ingest server's security group. [Security group references](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)

We'll grant the consumer instance access later too.

### 3. What order will we follow?

1. Create the RabbitMQ EC2 instance.
2. Configure the security group and SSH access.
3. Install RabbitMQ and create an application account.
4. Verify that the RabbitMQ service is running correctly.
5. Set the Java runtime environment variables.
6. Run the connection check and verify that all ten ski resort queues were created.
7. Restart RabbitMQ and check whether the Java application recovers automatically.

**Open the existing Tomcat instance and send screenshots of its Networking and Security tabs, or tell me the information in the table.** Keep passwords and the contents of your `.pem` private key to yourself.

Once you've confirmed the settings, I'll walk you through the new instance configuration on your AWS screen so you select the correct Region, VPC, and security group.

> Can you summarize what I've done so far in three sentences?

You copied Assignment 1 to create the Assignment 2 project, loaded both Maven projects in IntelliJ, and added the RabbitMQ dependency to the server project.
You wrote the swipe message model, RabbitMQ configuration, shared connection manager, channel pool, background recovery, and queue topology code, and all of it compiled successfully.
You also integrated and verified the data for all 40 lift vertical drops at runtime, and your next step is to deploy a dedicated RabbitMQ EC2 instance and verify the connection, queue creation, and recovery after a disconnection.

> Have I already started the instance? Is any information still missing?
> What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-afaaf053-0fca-46b1-8e30-42c59697ac29.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-0ca93822-12a1-4f3c-89ff-2646f2cbc02b.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-28ea99cd-7737-4fa2-b2ae-fe247abd381d.png`

<details><summary>2 previous messages</summary>

> The screenshot shows that the existing ingest server is `Running`, but its status checks are still initializing. Tomcat's status is separate. I'll use the Region, VPC, and security group shown in the screenshots to walk you through creating a dedicated RabbitMQ instance.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html`
>
> </details>
</details>

**Yes, your original Assignment 1 instance has started and shows `Running`.** Its **Status check** still shows **`Initializing`**. Wait and refresh to see whether the checks pass. Tomcat's startup is separate from the EC2 instance startup.

This is your original ingest server. **Create a separate EC2 instance for RabbitMQ.**

### Do we have all the information?

The screenshots show the main network settings:

| Item | Current value |
|---|---|
| Region | Oregon, `us-west-2` |
| Availability Zone | `us-west-2b` |
| VPC | `vpc-043df75c63094004a` |
| Subnet | `subnet-0106b5baa51f6e3aa` |
| Ingest server security group | `cs6650-lab1-sg` |
| Security group ID | `sg-04ba9f499622a5f2b` |
| Ingest server private IP | `172.31.34.32` |
| Current public IP | `34.215.144.122` |

**Confirm the SSH key yourself.** Find **Key pair name** in the instance's **Details** tab and make sure you have the corresponding `.pem` file on your computer. Keep the private key contents to yourself.

### Next, create the RabbitMQ instance

Click the orange **Launch instances** button in the upper right and enter these settings.

**1. Name and operating system**

| Setting | Value |
|---|---|
| Name | `cs6650-a2-rabbitmq` |
| Operating system | Amazon Linux |
| AMI | Amazon Linux 2023 |
| Architecture | 64-bit x86 |

I'll guide you through installation on Amazon Linux 2023, so use that operating system here.

**2. Instance type**

Start with:

```text
t3.small
```

This is our starting point for deployment and integration testing. After load testing, we'll decide whether to change it. If the course lab account does not allow this type, send me the message it shows.

**3. Key pair**

Choose a key pair whose private key you have on your computer. You can reuse the original key pair. Make sure you select a key pair so you can log in. AWS lets you choose an existing key pair when launching an instance. [AWS instance launch guide](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html)

**4. Network settings**

Click **Edit** and set:

| Setting | Value |
|---|---|
| VPC | Same as the original instance, `vpc-043df75c63094004a` |
| Subnet | Use the same subnet for now, `subnet-0106b5baa51f6e3aa` |
| Auto-assign public IP | Enable so you can connect over SSH from your Mac |
| Firewall | Create security group |
| Security group name | `cs6650-a2-rabbitmq-sg` |

Create a **new security group**, then add these inbound rules:

| Type | Port | Source | Purpose |
|---|---:|---|---|
| SSH | 22 | **My IP** | Log in to the instance from your Mac |
| Custom TCP | 5672 | Original ingest server security group, `sg-04ba9f499622a5f2b` | Let the ingest server reach RabbitMQ |

For the second rule, choose **Custom** as the source, enter the security group ID, and select the match. A security group reference lets instances associated with that group access the specified port over private IPs. [Security group rules](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)

Leave the management interface port, `15672`, closed for now. You can access it through an SSH tunnel later. We'll add consumer access when we deploy the consumer.

**5. Storage and instance count**

- Storage: `20 GiB`, `gp3`.
- Instance count: `1`.
- Advanced details: Keep the defaults for now.

**Stop here for this step and send a screenshot of the new instance's Network settings.** Once we've checked the VPC, subnet, and both security group rules, you can click **Launch instance**, then log in over SSH and install RabbitMQ.

> Why do we need two separate servers? What's the difference between the Assignment 1 instance and the RabbitMQ server, and what does each one do?
> My first instance uses `t3.micro`, as the instructor required for the lab. Should I use `t3.small` here or stick with `t3.micro` for consistency? Can I use `cs6650-lab1-key` as the key pair, or should I create a new one?
> Why do so many settings on the new server match `cs6650-lab1`? What would happen if they differed? When creating the security group, I couldn't find a port setting next to Custom. Did I set it up correctly in the screenshots? Is there anything I should change?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-60f043fc-2c8c-486a-9b75-eb77f1496eb5.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4a88a559-ca6e-470f-828d-e7390ff6264a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-f5d68e26-6e51-48c5-9a27-11ddc8feb83d.png`

<details><summary>2 previous messages</summary>

> You selected the correct operating system, VPC, and subnet. In the security group screenshot, `Custom` is the source type. The rule type is still SSH, so the port is still 22. I'll explain how to add port 5672 and what each instance does.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://aws.amazon.com/ec2/instance-types/t3/`
>
> Searched the web for `site.aws.amazon.com t3.micro t3.small 1 GiB 2 GiB ...`
>
> </details>
</details>

**You've selected the correct operating system, VPC, subnet, and security group name. You still need to select a key pair, set the SSH source, and add a rule for port 5672.**

### 1. Why do we need two servers?

They have different responsibilities, and the assignment explicitly requires **RabbitMQ to run on a dedicated EC2 instance**.

| Instance | What it runs | Responsibilities |
|---|---|---|
| Original Assignment 1 instance | Tomcat and your `server-v2` | Receives HTTP requests, validates data, publishes events, and maintains WebSocket connections |
| New RabbitMQ instance | RabbitMQ service | Receives messages, stores them in queues, and delivers them to consumers |

```text
Client
    ↓ HTTP
Ingest server: Tomcat + server-v2
    ↓ AMQP, port 5672
RabbitMQ server
    ↓
Consumer server: will be deployed separately later
```

**The consumer application calculates cumulative vertical feet and leaderboards.**

Separate instances also let you adjust resources and investigate bottlenecks independently. If messages build up in RabbitMQ, for example, you can examine the broker and the consumer separately from Tomcat.

### 2. Does the new instance have to match the original `t3.micro`?

**The instance types can differ. You can leave the original ingest server on `t3.micro`.**

An instance type determines the machine's resources. Machines with different resource specifications can still communicate.

| Type | vCPU | Memory |
|---|---:|---:|
| `t3.micro` | 2 | 1 GiB |
| `t3.small` | 2 | 2 GiB |

[AWS T3 specifications](https://aws.amazon.com/ec2/instance-types/t3/)

I suggested `t3.small` to give the broker more memory. The assignment doesn't require that size. RabbitMQ blocks publishing connections when it hits a memory or disk alarm threshold, so available resources can affect load testing. [RabbitMQ resource alarms](https://www.rabbitmq.com/docs/alarms)

- **If the instructor requires `t3.micro` for every instance, use `t3.micro`.**
- If the prior lab required it only for the original instance and Assignment 2 has no such restriction, you can use `t3.small` for RabbitMQ.
- You can start low-load integration testing on a micro instance. Verify separately whether it meets the full load test targets.

### 3. Can I use `cs6650-lab1-key`?

**Yes. AWS lets you reuse a key pair across instances.** [AWS key pair documentation](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-key-pairs.html)

Make sure you still have the corresponding file on your computer:

```text
cs6650-lab1-key.pem
```

No key pair appears to be selected in your screenshot. Choose **`cs6650-lab1-key`** from the dropdown.

The key proves that you can log in over SSH. File sharing and communication between instances depend on separate settings.

### 4. Why do many network settings match? What if they differ?

| Setting | Does it need to match? | Why it matters |
|---|---|---|
| Region | Keep it the same for this setup | Avoid cross-Region networking and latency |
| VPC | Keep it the same for this setup | Simplifies private IP communication. Different VPCs usually need extra network connectivity |
| Subnet | Can differ | Subnets in the same VPC can usually communicate if routes, security groups, and network ACLs allow it |
| Availability Zone | Can differ | Instances in different Availability Zones can communicate |
| Operating system | Can differ | Using the same OS makes the installation steps easier to follow |
| Instance type | Can differ | Choose based on each workload |
| Key pair | Can differ | Reuse an existing key pair or create separate ones |
| Security group | Create separate groups for this setup | The ingest server and RabbitMQ need different inbound service ports |

For example, the same VPC can contain:

```text
Ingest server: subnet A
RabbitMQ: subnet B
```

Instances can still communicate across those subnets.

**Using the same Region, VPC, and subnet reduces the number of variables for this initial deployment.** Distributed systems can use different settings when the network is configured accordingly.

### 5. Why can't I change the port next to `Custom`?

The screenshot shows two separate settings:

- **Type** determines the protocol and port type, such as SSH or Custom TCP.
- **Source type** determines who can connect, such as My IP or Custom.

You changed **Source type → Custom**, while **Type** is still **SSH**. SSH uses port `22` in this preset, so the port field is grayed out. That's expected.

### 6. How should I configure the security group?

**First rule: keep SSH and set its source**

Set the existing rule to:

| Field | Value |
|---|---|
| Type | SSH |
| Protocol | TCP, filled in automatically |
| Port range | 22, filled in automatically |
| Source type | **My IP** |

Selecting My IP should fill in a source like `your-public-IP/32`. The Source field is still empty in your screenshot, so fill it in.

**Second rule: add RabbitMQ access**

Click **Add security group rule**, then enter:

| Field | Value |
|---|---|
| Type | **Custom TCP** |
| Protocol | TCP |
| Port range | **5672** |
| Source type | **Custom** |
| Source | Search for and select `sg-04ba9f499622a5f2b` |
| Description | `AMQP from ingest servers` |

This selects the original ingest server's `cs6650-lab1-sg` security group.

> Allow ingest servers associated with this security group to connect to RabbitMQ on port 5672 over the private network.

The rule references the original security group. Its rules stay separate, and access is limited to the selected source group. [AWS security group references](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)

You should end up with **two rules**:

```text
SSH         TCP 22     Source: My IP
Custom TCP  TCP 5672   Source: cs6650-lab1-sg
```

### 7. What else should I change in the screenshots?

- **Key pair:** Select `cs6650-lab1-key`.
- **Instance type:** Follow any course restrictions. If the course doesn't require micro instances across the board, I suggest `t3.small` for RabbitMQ.
- **Storage:** It's currently `8 GiB`. Increase it to the proposed **`20 GiB gp3`** to allow space for the operating system, RabbitMQ data, and logs. This is a starting recommendation.
- **Security group:** Complete both rules above.
- Keep the name, Amazon Linux 2023, current VPC, subnet, and public IP auto-assignment as shown.

Once you've filled in those settings, click **Launch instance**. When the instance shows `Running` and its status checks pass, the next step is to log in over SSH and install RabbitMQ.

> Can you check whether this is right before I click Launch?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-335b3efb-bac2-4b2f-a30e-73372153675c.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8d9dd95d-330e-4f72-b644-1ae3361e09a1.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-80515ae5-e4b8-44a0-909a-1c138abbf967.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d724498c-c06a-4113-a6bc-39f3251350ee.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-f3ffea3b-6ca0-4d68-a323-c5cda751c520.png`

**The overall configuration looks right. Update the SSH source before launching.**

In the fourth screenshot, `My IP` automatically entered:

```text
2607:…/32
```

That's an **IPv6 address**. We plan to log in using the instance's public **IPv4** address, so this rule won't allow that connection.

### 1. Update the SSH source

Run this in **Terminal on your Mac**:

```bash
curl -4 https://checkip.amazonaws.com
```

- `curl` asks the website for your public IP address.
- `-4` forces the request to use IPv4.

The output should look something like:

```text
123.45.67.89
```

Then edit the first SSH rule:

| Field | Setting |
|---|---|
| Type | Keep SSH |
| Port range | Keep 22 |
| Source type | Change to **Custom** |
| Source | Remove the IPv6 entry and enter the IPv4 address you just looked up, followed by `/32` |

For example:

```text
123.45.67.89/32
```

Here, `/32` allows just that one IPv4 address. **Use the address from your own lookup.** If switching Wi-Fi networks or VPNs changes your public IP, update this rule.

### 2. Review of the other settings

| Setting | Result |
|---|---|
| Name `cs6650-a2-rabbitmq` | Correct |
| Amazon Linux 2023, x86_64 | Correct |
| `t3.small` | Matches the plan if the course doesn't restrict instance types |
| `cs6650-lab1-key` | Correct. Make sure you have the corresponding `.pem` file locally |
| VPC and subnet | Correct. They match the original ingest instance |
| Auto-assign public IP: Enable | Correct |
| New security group `cs6650-a2-rabbitmq-sg` | Correct |
| TCP 5672 source `sg-04ba9f499622a5f2b` | **Correctly selected** |
| 20 GiB, gp3 | Correct |
| File systems: None | Correct |

**After updating the SSH source, click `Launch instance`.**

Wait until the instance is **`Running` and its status checks pass**. Then select the new instance and share the public and private IP addresses from **Details** so we can continue with SSH and RabbitMQ installation.

> Why do we need to use an IPv6 address? What kind of address did my first instance use?

**We can use IPv4 for this setup.** AWS's **My IP** option automatically detected IPv6 on your current network and filled it into the rule.

The earlier screenshot of your first instance showed:

| Address type | Address | Purpose |
|---|---|---|
| Public IPv4 | `34.215.144.122` | Lets your Mac reach the instance over the internet |
| Private IPv4 | `172.31.34.32` | Lets servers communicate within the same VPC |
| IPv6 | Unassigned | Currently unused |

The public IPv4 address may change when you stop and restart the instance. The table shows the value from your earlier screenshot.

The two addresses play different roles:

```text
Your Mac's public IP [Source in the security group]
    ↓ SSH
EC2's public IP [destination you connect to]
```

**My IP** in a security group refers to the public address of your computer's network. For the new instance's SSH rule, use **your public IPv4 address followed by `/32`**. The first instance's `34.215.144.122` is an EC2 address.

The first instance's earlier SSH rule also used an IPv4 source, such as `63.208.141.32/29` in the screenshot. This time, `/32` restricts access to your current public IPv4 address alone.

> Does this look right now?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4c10e052-b5d7-4dd8-95c9-4f67434dc227.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8754e482-961e-418f-8a41-e89b3fc14a4a.png`

**Yes. The configuration is correct now, and you can click `Launch instance`.**

Confirmed:

- SSH on port `22` allows the public IPv4 address you looked up, **`172.56.107.120/32`**.
- RabbitMQ on port `5672` allows the ingest server's security group, **`sg-04ba9f499622a5f2b`**.
- Instance type: `t3.small`.
- Storage: `20 GiB gp3`.

After launching:

1. Click **View all instances**.
2. Select **`cs6650-a2-rabbitmq`**.
3. Wait for the instance to show **Running** and pass its **Status check**.
4. Find **Public IPv4 address** and **Private IPv4 address** in **Details**.

Send me those two addresses. Next we'll log in to the new instance over SSH and install RabbitMQ.

> Does this look right now?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bbfb7544-6826-49dc-87b1-71f328a61c6d.png`

**Yes. The new RabbitMQ instance has been created and shows `Running`.** The status check still shows `Initializing`. Wait for **`3/3 checks passed`** before continuing.

The screenshot shows these addresses:

| Address | Purpose |
|---|---|
| Public IP: `54.245.54.122` | SSH login from your Mac |
| Private IP: `172.31.45.116` | RabbitMQ connections from the ingest server and consumer later |

**Next, log in to the new instance from your Mac.**

With the new instance selected in AWS, click **Connect → SSH client**.

The page shows an example command. If your private key is saved at the same path you used before, `~/.ssh/cs6650-lab1-key.pem`, run this in **Terminal on your Mac**:

```bash
ssh -i ~/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

- `-i` specifies the private key file.
- `ec2-user` is the Amazon Linux login username.
- `54.245.54.122` is the public address of the new RabbitMQ instance.

If the key is in a different directory, change the path in the command to its actual location.

If SSH asks you to confirm the host's authenticity on the first connection, verify the fingerprint using the guidance on the AWS **SSH client** page before typing `yes`.

**Once you log in, the terminal prompt should look something like this:**

```text
[ec2-user@ip-172-31-45-116 ~]$
```

That means you're on the RabbitMQ EC2 instance. Finish logging in and send me the success screen or error message. Then we'll install RabbitMQ.

> What should I do?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b2c25550-cdb1-4880-9e20-e2c0405d998a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-c005013a-4a90-4c31-be00-451ab45d110a.png`

The **In SSH client** page in your second screenshot is the right place. Do the next steps in **Terminal on your Mac**. You can leave the browser-based **Connect** button in the first screenshot alone.

**1. Find the private key file**

In Finder, locate:

```text
cs6650-lab1-key.pem
```

This is the private key you used for Assignment 1. Reuse that file.

**2. Set the private key permissions**

Open Terminal on your Mac and enter the following, with a space at the end:

```bash
chmod 400
```

Then drag the `.pem` file from Finder into Terminal. Terminal will fill in the path. Press Return.

`chmod 400` gives only the file owner permission to read the private key. **No output usually means it worked.**

**3. Log in over SSH**

Enter the following in Terminal, again with a space at the end:

```bash
ssh -i
```

Drag the `.pem` file into Terminal again, then add:

```text
 ec2-user@54.245.54.122
```

The full command should look like:

```bash
ssh -i /your/actual/path/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

Press Return to run it.

- The path after `-i` points to your private key.
- `ec2-user` is the Amazon Linux username.
- `54.245.54.122` is the new RabbitMQ instance's public IP.

**4. Check the connection result**

On your first connection, SSH may show the host fingerprint and ask:

```text
Are you sure you want to continue connecting (yes/no/[fingerprint])?
```

This prompt asks you to confirm the host's identity. You can send me the prompt first. Keep the private key contents to yourself.

If the login succeeds, you'll see something like:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

That prompt means your terminal session is on the EC2 instance.

The screenshot shows **`2/3 checks passed`**. This may still allow SSH, so you can try connecting. If it times out or reports another error, send the terminal message and we'll troubleshoot that specific error.

> How can I find it in Finder?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-30b62626-f4ab-45f0-88e7-850a3d72bdf6.png`

The two `README.md` files probably showed up because **their contents mention the key's filename**. The key itself is a `.pem` file.

First, open the `.ssh` folder used in the previous assignment's deployment instructions:

1. In Finder, press **`Command + Shift + G`** to open Go to Folder.
2. Enter:

   ```text
   ~/.ssh
   ```

3. Press Return and look for:

   ```text
   cs6650-lab1-key.pem
   ```

Finder normally hides `.ssh`. Go to Folder opens it directly.

**If the file isn't there, press `Command + Shift + G` again and enter:**

```text
~/Downloads
```

Look for the `.pem` file in Downloads.

Once you find it, **drag it into Terminal to insert its path**. You can leave the file closed. If it isn't in either location, tell me and we'll search by filename from Terminal.

> I found it. What should I click here?

> I found it. What should I click here?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-08e8a121-1e45-4e0e-9f0f-adeaf6a6893f.png`

**Click `Cancel`.** Double-clicking the `.pem` file opened the Keychain import dialog. SSH can use the file directly.

Now that you've found the file:

1. Return to Finder and keep the folder window open. Leave the file closed.
2. Open Terminal on your Mac and type `chmod 400` followed by a space.
3. **Drag the `.pem` file from Finder into Terminal**, then press Return.

Then type `ssh -i` followed by a space, drag the same file into Terminal again, and append:

```text
 ec2-user@54.245.54.122
```

The complete command will look like this:

```bash
ssh -i /actual/file/path/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

Press Return and send me the connection result shown in Terminal.

> What exact command should I enter?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8a45b2c6-e345-4546-bf16-a442b05be30a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-10862026-57a2-4f08-bc15-c0b1a843316d.png`

The prompt appeared because you ran the command and the file path separately. The file is intact, and the SSH login attempt hasn't happened yet.

Copy these two complete commands, **one at a time, pressing Return after each one**.

**First, set the private key permissions**

```bash
chmod 400 /Users/zixin/.ssh/cs6650-lab1-key.pem
```

If Terminal prints nothing and returns to `zixin@Mac ~ %`, that's expected.

**Second, log in to the RabbitMQ instance**

```bash
ssh -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

Here, `-i` specifies the private key, and `ec2-user@54.245.54.122` specifies the login user and destination server.

After running it, send me the Terminal output. If the first connection shows a `yes/no/[fingerprint]` prompt, pause there.

> Is this right? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e4c07cec-9dd6-401c-a63f-0d0296ce9b9f.png`

<details><summary>2 previous messages</summary>

> You've successfully logged in to the RabbitMQ EC2 instance. Next, install Docker and use it to run RabbitMQ. You'll still deploy and manage RabbitMQ yourself on this EC2 instance, and your Java code will still handle channel pooling and reconnection.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `site.docs.aws.amazon.com Amazon Linux 2023 sudo yum install docker systemctl enable docker ...`
>
> </details>
</details>

**Yes, you've successfully logged in to the RabbitMQ instance.** The prompt at the bottom:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

means that commands you enter now run **on EC2**. Your Mac is running the SSH client.

Next, install **Docker** and run RabbitMQ in a container. This is still a self-managed RabbitMQ deployment on your EC2 instance. RabbitMQ officially documents this installation option. [RabbitMQ installation guide](https://www.rabbitmq.com/docs/download)

### 1. Install Docker

Copy this entire command into your current SSH session and press Return:

```bash
sudo yum install -y docker
```

- `sudo` runs the command with administrator privileges.
- `yum install` installs software from the system's package repositories.
- `-y` automatically confirms the installation.
- `docker` is the package to install.

AWS documents this method for Amazon Linux 2023. [AWS installation guide](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-docker.html)

Wait for the command to finish. You'll usually see:

```text
Complete!
```

### 2. Start Docker and enable it at boot

After the first command finishes, run:

```bash
sudo systemctl enable --now docker
```

- `systemctl` manages system services.
- `enable` configures Docker to start automatically whenever the EC2 operating system boots.
- `--now` starts Docker immediately.

**This starts the Docker service. We'll configure the RabbitMQ container's restart behavior separately in the next step.**

### 3. Check that Docker works

Run:

```bash
sudo docker info
```

If the output contains both **Client** and **Server** information and doesn't show:

```text
Cannot connect to the Docker daemon
```

then Docker is ready to use.

**Run the three commands in order, waiting for each one to finish.** Send me the final `sudo docker info` output. Then we'll create the RabbitMQ container, set up persistent storage, and create the application account.

> What does Docker do? The screenshots show that I've installed it. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-27d5d34e-b6ff-44fb-bdcb-3e0f694b740b.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-07a82f43-07af-49a4-822a-552791f5d848.png`

**`Complete!` in the screenshot means Docker installed successfully.** The screenshots haven't shown Docker starting yet. Start it and check its status next.

**What does Docker do?**

Docker runs containers. A container packages an application and its runtime dependencies into a relatively isolated environment, making the application easier to install and start.

In this assignment:

```text
RabbitMQ EC2: a cloud server
└── Amazon Linux: the operating system
    └── Docker: the container runtime
        └── RabbitMQ container: runs the message broker
```

- **Image:** The template containing RabbitMQ and its dependencies.
- **Container:** A running application instance created from the image.

Docker saves you from installing and matching versions of RabbitMQ, Erlang, and other dependencies separately. **RabbitMQ still runs on your own EC2 instance, where you manage it. Your Java code still handles channel pooling and reconnection.**

**Run the following commands in order in your current EC2 terminal.**

First, start Docker and enable it at boot:

```bash
sudo systemctl enable --now docker
```

- `enable` configures Docker to start automatically when the system boots.
- `--now` starts it immediately.

Second, verify that the service is running:

```bash
sudo systemctl is-active docker
```

Expected output:

```text
active
```

Third, check that Docker is working:

```bash
sudo docker info
```

You should see both **Client** and **Server** sections without a `Cannot connect to the Docker daemon` error.

**Wait for each command to finish before running the next.** Send me the final output. Then we'll create the RabbitMQ container and configure persistent storage and automatic restarts.

> Is this asking for the password I use to log in to my Mac?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-6b9c8cbd-7f98-4dee-b8b6-6e284d540e20.png`

**Press `Control + C` to cancel before entering a password.**

The screenshot shows that the SSH session ended:

```text
Connection to 54.245.54.122 closed.
```

The prompt has also changed back to:

```text
zixin@Mac ~ %
```

You ran `sudo` **locally on your Mac**, so it is asking for your Mac login password. This command belongs on EC2. Cancel it locally and reconnect first.

**1. Log back in to EC2:**

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

The new `ServerAliveInterval` setting sends a keepalive probe every 30 seconds to help detect a broken connection. Network interruptions can still happen.

**2. Confirm that you see the remote prompt:**

```text
[ec2-user@ip-172-31-45-116 ~]$
```

**3. Then run:**

```bash
sudo systemctl enable --now docker
```

Under the default configuration on this Amazon Linux instance, `ec2-user` usually doesn't need a password to use `sudo`.

**4. Check the result:**

```bash
sudo systemctl is-active docker
```

The expected output is `active`. Then run:

```bash
sudo docker info
```

Before entering server commands, check the prompt. **`zixin@Mac` means you're on your Mac. `ec2-user@ip-…` means you're on EC2.**

> What does this mean?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-854653a9-4316-4e0f-b23a-aadd7c2c8fa2.png`

This is **SSH's command usage message**. The command you entered was missing the destination at the end:

```text
ec2-user@54.245.54.122
```

That means the SSH session hasn't reconnected and you're still on your Mac.

**Copy the entire line below, then press Return:**

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

Wait for the remote prompt:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

Then run these commands in order, waiting for each to finish:

```bash
sudo systemctl enable --now docker
```

```bash
sudo systemctl is-active docker
```

The second command should print `active`. Then run:

```bash
sudo docker info
```

Docker is already installed, so you can skip reinstalling it.

> Did this work? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-0f9969ea-38ec-408c-9655-fe67f5b71afc.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-3d8e8b4b-e903-4312-b3ee-9b3eca064e2e.png`

<details><summary>2 previous messages</summary>

> Yes, Docker is running correctly. The screenshots show the service as `active`, and `docker info` displays server information. Next, create the RabbitMQ container and configure persistent storage, automatic restarts, and the management interface port.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://hub.docker.com/_/rabbitmq`
>
> </details>
</details>

**Yes, it worked.** Docker is `active` in the screenshot, and the full Server information is visible. `Containers: 0` and `Images: 0` mean that no containers have been created or images downloaded yet.

Next, start RabbitMQ in **your current SSH session on EC2**.

### 1. Create a persistent data volume

Run:

```bash
sudo docker volume create skitrak-rabbitmq-data
```

Expected output:

```text
skitrak-rabbitmq-data
```

The volume stores RabbitMQ data. You can mount it again if you replace the container. **The volume stays on this EC2 instance's disk. A separate backup would require a separate copy.**

### 2. Create and start the RabbitMQ container

**Copy this entire line and press Return:**

```bash
sudo docker run -d --name skitrak-rabbitmq --hostname skitrak-rabbitmq --restart unless-stopped -p 5672:5672 -p 127.0.0.1:15672:15672 -v skitrak-rabbitmq-data:/var/lib/rabbitmq --log-opt max-size=10m --log-opt max-file=3 rabbitmq:4.3.7-management
```

The first run downloads the image automatically, so give it time. We pin a specific version of the official image so the deployment can be reproduced later. [Official RabbitMQ image](https://hub.docker.com/_/rabbitmq)

| Option | Purpose |
|---|---|
| `run -d` | Creates the container and runs it in the background, so closing SSH leaves it running |
| `--name skitrak-rabbitmq` | Names the container for later management |
| `--hostname skitrak-rabbitmq` | Gives the container a fixed hostname to keep the RabbitMQ node identity stable |
| `--restart unless-stopped` | Restarts the container after it exits or Docker restarts, unless it was stopped manually |
| `-p 5672:5672` | Maps EC2 port 5672 to the container's AMQP port. The security group still controls access |
| `-p 127.0.0.1:15672:15672` | Binds the management interface to the EC2 loopback address for later access through an SSH tunnel |
| `-v ...:/var/lib/rabbitmq` | Stores RabbitMQ data in the volume you just created |
| The two `--log-opt` options | Rotate container logs to limit their growth |
| `rabbitmq:4.3.7-management` | Uses the RabbitMQ image that includes the management plugin |

Docker restarts the service process. **Your Java code reconnects the application to the service.** [Docker restart policies](https://docs.docker.com/engine/containers/start-containers-automatically/)

### 3. Check the container status

After the previous step finishes, run:

```bash
sudo docker ps
```

You should see:

- `NAMES`: `skitrak-rabbitmq`
- `STATUS`: `Up ...`

This confirms that the container is running. RabbitMQ itself may still be initializing.

### 4. Check the RabbitMQ process

Wait about 20–30 seconds, then run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmq-diagnostics -q ping
```

Expected output:

```text
Ping succeeded
```

`exec` runs a command inside the running container. The diagnostics tool checks whether the RabbitMQ node responds.

If the check fails, look at the logs first:

```bash
sudo docker logs --tail 50 skitrak-rabbitmq
```

**Send me the results of `docker ps` and the ping check.** Next, we'll create a dedicated application user and grant permissions. The Java application's login credentials and the ten ski resort queues are still pending.

> What percentage of the assignment have I finished, and how much is left?
> Should I reconnect and create the persistent data volume again?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-7527c2c2-455d-4977-9802-6e45059e3832.png`

**Reconnect and verify the existing container and volume.** The screenshot shows that the RabbitMQ container started before SSH disconnected. A background container normally keeps running after the SSH session ends.

Based on the implementation, deployment, verification, and reporting work, **roughly 20%–25% is complete and 75%–80% remains**. This estimates the workload. The pool and recovery code compile and still need integration testing with a running broker.

| Area | Remaining work |
|---|---|
| RabbitMQ integration | Verify the volume, broker health, user permissions, Java connection, and recovery |
| Producer | Publish messages and wire publishing into the Servlet |
| Consumer | Implement ordered consumption, deduplication, cumulative vertical feet, top N rankings, retries, and dynamic thread management |
| Live updates | Implement the broadcast backplane, WebSocket updates, and client integration |
| Deployment and testing | Deploy the separate consumer, run two and four ingest servers, configure ALB and monitoring, and run load tests |
| Submission materials | Prepare the architecture diagram, test analysis, AI reflection, and PDF |

**First, reconnect and verify the container that's already there.**

**1. Run the complete command in Terminal on your Mac:**

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

Confirm the prompt changes to:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

**2. Check whether the container is still running:**

```bash
sudo docker ps
```

Reuse the existing container without running `docker run` again.

**3. Verify that the container actually mounts the data volume:**

```bash
sudo docker inspect --format '{{json .Mounts}}' skitrak-rabbitmq
```

The output should include:

```text
"Name":"skitrak-rabbitmq-data"
"Destination":"/var/lib/rabbitmq"
```

Inspecting the mount confirms that **the container uses the volume**. Running `volume create` again would only establish that the volume exists.

**4. Check RabbitMQ:**

```bash
sudo docker exec skitrak-rabbitmq rabbitmq-diagnostics -q ping
```

Expected output:

```text
Ping succeeded
```

Send me the results of steps 3 and 4. Once those are confirmed, we'll create the application user and set its permissions.

> Okay, it says “succeeded.” What should I do next?

<details><summary>2 previous messages</summary>

> `Ping succeeded` means the RabbitMQ node is responding. Next, create the account the Java application will use and grant it access to the default virtual host, `/`. Enter the password in your terminal and keep it to yourself.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/docs/access-control`
>
> </details>
</details>

**The RabbitMQ node passed the check. Next, create an application account so your Java program can connect, create queues, and publish messages.**

This step only configures the account in RabbitMQ. **Leave the Java files as they are.**

### 1. Confirm you're still in the EC2 terminal

The prompt should be:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

Run the commands below in that terminal.

### 2. Create the `skitrak` account

Copy the full command:

```bash
sudo docker exec -it skitrak-rabbitmq rabbitmqctl add_user skitrak
```

| Part | Purpose |
|---|---|
| `docker exec` | Runs a command in a running container |
| `-it` | Allows you to enter the password interactively |
| `skitrak-rabbitmq` | Container name |
| `rabbitmqctl` | RabbitMQ administration tool |
| `add_user skitrak` | Creates a user named `skitrak` |

At the password prompt, **set a new password for RabbitMQ**. This is separate from your Mac or AWS login. Enter and confirm it as prompted. It's normal for the characters to be hidden as you type.

Save the password in your password manager for the Java configuration later. **Keep it to yourself.** RabbitMQ supports interactive user creation. [Account management documentation](https://www.rabbitmq.com/docs/access-control)

### 3. Grant permissions on the default virtual host

After creating the account, run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl set_permissions -p / skitrak ".*" ".*" ".*"
```

- `-p /` selects the default virtual host, which matches the default in our Java configuration.
- `skitrak` is the user receiving permissions.
- The three `".*"` patterns grant configure, write, and read access, respectively, to all resources in this virtual host.

| Permission | Purpose in our application |
|---|---|
| Configure | Declare exchanges and queues |
| Write | Publish messages and perform related operations |
| Read | Consume messages and perform related operations |

This is the initial permission scope for the assignment. **It applies to this virtual host.**

### 4. Verify the configuration

Run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_users
```

You should see `skitrak`. The `[]` next to it is normal and means that the user has no additional administrative tags.

Then run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_user_permissions skitrak
```

The output should include:

```text
vhost   configure   write   read
/       .*          .*      .*
```

**The account and permissions are ready. The remote Java connection still needs to be tested.** Send me the permission output. Next we'll run a connection check from IntelliJ through an SSH tunnel, with RabbitMQ port 5672 kept closed to direct public access from your Mac.

> What does this mean?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-f1fa88d3-3c6e-45dd-af06-6c647b83be6b.png`

The command is waiting for you to **set a new password for the RabbitMQ user `skitrak`**. Account creation is still in progress.

The warning means:

> This Erlang/OTP version can't hide terminal input, so the password you type will be visible on the screen.

I said earlier that characters would be hidden. That doesn't apply to your version. Follow the actual prompt.

1. Enter a new password after `Password:` and press Return.
2. If asked to confirm it, enter it again.
3. Save it in your password manager for the Java connection settings later.

**Use a new RabbitMQ password, separate from your Mac and AWS passwords. Keep any terminal screenshot that shows the password private.**

After the account is created, set its permissions:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl set_permissions -p / skitrak ".*" ".*" ".*"
```

Then verify them:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_user_permissions skitrak
```

Send **only the output of this last permission check**. Leave out the earlier part of the terminal output that shows the password.

> It worked. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-81fd7756-a04d-4125-bd94-c71e79377bce.png`

<details><summary>2 previous messages</summary>

> The account and its permissions on `/` are configured. Next, run the Java connection check from IntelliJ through an SSH tunnel to verify the connection manager, channel pool, and queue topology. Your screenshot also shows the password you just entered. I recommend changing it first and will keep it out of this reply.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/docs/man/rabbitmqctl.8`
>
> </details>
</details>

**It worked. `skitrak` now has configure, write, and read permissions on the default virtual host, `/`.** Next, connect the Java program in IntelliJ to RabbitMQ.

Your screenshot shows the password you just entered. Change it first and keep the new password private.

### 1. Change the password

Run these three commands in your current **EC2 terminal**.

First:

```bash
read -r -s -p "New RabbitMQ password: " rabbitmq_password
```

Enter a new password and press Return. **Bash hides the input this time.** The command only stores it in a temporary variable.

Second:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl change_password skitrak "$rabbitmq_password"
```

This passes the password to RabbitMQ to update the account. [Command documentation](https://www.rabbitmq.com/docs/man/rabbitmqctl.8)

Third:

```bash
unset rabbitmq_password
```

This clears the temporary shell variable. The account and its permissions are already in place.

### 2. Open a new Mac terminal and establish an SSH tunnel

Keep the existing EC2 session open. Press **`Command + N`** to open a new terminal window and confirm the prompt is:

```text
zixin@Mac ~ %
```

Copy and run the full command:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

**If the command stays in the foreground without output or a new prompt, the tunnel is usually running. Keep this window open.**

The command establishes this path:

```text
Mac 的 127.0.0.1:5673
    ↓ SSH 隧道
RabbitMQ EC2 的 127.0.0.1:5672
    ↓ Docker 端口映射
RabbitMQ 容器
```

| Option | Purpose |
|---|---|
| `-N` | Opens only the tunnel, without a remote shell |
| `-L ...` | Forwards a local Mac port to EC2 |
| `127.0.0.1:5673` | Address the Java program in IntelliJ connects to |
| The later `127.0.0.1:5672` | RabbitMQ address as seen from EC2 |
| `ExitOnForwardFailure=yes` | Exits if local port forwarding cannot be established |

Using local port `5673` reduces the chance of conflicting with a service already on your Mac's port `5672`. **The AWS security group can leave port 5673 closed.**

### 3. Create the Java connection check

In IntelliJ, go to:

```text
server-v2/src/test/java
```

Create a **`messaging` package** there, then create:

```text
RabbitMqConnectionCheck
```

Full path:

```text
server-v2/src/test/java/messaging/RabbitMqConnectionCheck.java
```

This is a standalone runnable check, separate from the HTTP API.

Enter:

```java
package messaging;

import com.rabbitmq.client.Channel;

import java.util.concurrent.TimeUnit;

public final class RabbitMqConnectionCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        // Close the manager and its resources when the check finishes.
        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            // Initialization runs on the manager's background worker.
            manager.start();

            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

            while (!manager.isConnected()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "RabbitMQ did not become ready within 60 seconds"
                    );
                }

                Thread.sleep(200);
            }

            System.out.println("PASS: connection and channel pool are ready.");

            // Keep the pool reference so the channel returns to the same pool.
            ChannelPool pool = manager.getChannelPool();
            Channel channel = pool.borrowChannel();

            try {
                // Verify that initialization created every resort queue.
                for (int resortID = 1; resortID <= 10; resortID++) {
                    String queueName =
                            RabbitMqTopology.routingKey(resortID);

                    channel.queueDeclarePassive(queueName);

                    System.out.println("PASS: queue exists: " + queueName);
                }
            } finally {
                pool.returnChannel(channel);
            }

            System.out.println("All RabbitMQ connection checks passed.");
        }
    }
}
```

What each part does:

- **Load configuration:** Reuse `RabbitMqConfig` and keep the password out of source code.
- **`try (...)`:** Automatically call the manager's `close()` when the check finishes to clean up the connection, pool, and background tasks.
- **`manager.start()`:** Start the background initialization logic for the first time.
- **Wait loop:** Wait up to 60 seconds and check readiness every 200 milliseconds. Use `nanoTime()` to measure elapsed time.
- **Borrow a channel:** Use the existing pool without creating another publisher channel.
- **`queueDeclarePassive()`:** Check that each queue exists. The topology class that the manager calls during initialization creates the queues.
- **`finally`:** Return the channel to its original pool whether the check succeeds or throws an exception.

### 4. Set IntelliJ runtime environment variables

Click the green triangle next to `main()` to run the check once. **The first run may report a missing `RABBITMQ_HOST`. That's expected before configuration.**

Open **Run → Edit Configurations** and select the newly created **`RabbitMqConnectionCheck`** configuration.

Find **Environment variables**. If it isn't visible, add it through **Modify options → Environment variables**.

| Variable | Value |
|---|---|
| `RABBITMQ_HOST` | `127.0.0.1` |
| `RABBITMQ_PORT` | `5673` |
| `RABBITMQ_USERNAME` | `skitrak` |
| `RABBITMQ_PASSWORD` | The new password you just set |
| `RABBITMQ_VHOST` | `/` |
| `RABBITMQ_CHANNEL_POOL_SIZE` | `16` |

**These are the settings for testing from your Mac through the tunnel.** When deploying to the ingest server later, use RabbitMQ's private IP `172.31.45.116` and port `5672`.

Save the configuration and keep any configuration containing the password out of Git.

### 5. Run the check again

Keep the SSH tunnel running and run the check again. Expect:

```text
PASS: connection and channel pool are ready.
PASS: queue exists: resort.1
...
PASS: queue exists: resort.10
All RabbitMQ connection checks passed.
```

The process should exit with code `0`.

**Success confirms that Java authentication, connection setup, pool initialization, and creation and verification of all ten resort queues actually ran.** Message publishing and recovery after a disconnect come next. Send the run output and keep the environment variable password out of screenshots.

> Did I add the settings correctly in screenshot 3? Can you explain exactly how to run the check in step 5?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-24b55da2-d2f6-459f-9853-a7c547ce3f23.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-95856ebb-21f5-4847-8524-e747c7e38e40.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-7f27fc26-731b-4543-968b-7722ceaa9c1a.png`

**Screenshot 3 shows the right field**, **Environment variables** for `RabbitMqConnectionCheck`. Only the end of the field is visible, so I can't verify that all variables are present.

Screenshot 2 shows that **the previous run lacked `RABBITMQ_HOST`**. Run the check again after saving the settings. The old output stays visible until you run it again.

1. Click the small edit icon at the far right of the **Environment variables** field.
2. Confirm that the list contains these six exact names:

   | Name | Value |
   |---|---|
   | `RABBITMQ_HOST` | `127.0.0.1` |
   | `RABBITMQ_PORT` | `5673` |
   | `RABBITMQ_USERNAME` | `skitrak` |
   | `RABBITMQ_PASSWORD` | Your new RabbitMQ password |
   | `RABBITMQ_VHOST` | `/` |
   | `RABBITMQ_CHANNEL_POOL_SIZE` | `16` |

   Enter each variable on its own row. This makes them easier to check and prevents special characters in the password from being interpreted as separators. Keep screenshots containing the password private.

3. Click **OK** in the variable editor.
4. Back in the window from screenshot 3, click **Apply** to save.
5. Keep the **SSH tunnel terminal** in screenshot 1 open. A running tunnel can sit there without output or a new prompt.
6. **Click `Run` at the bottom of screenshot 3**, the button with the green triangle. Use **Run**, with Coverage and Maven `compile` left alone.

Check the new output at the bottom of IntelliJ. Expect:

```text
PASS: connection and channel pool are ready.
PASS: queue exists: resort.1
...
PASS: queue exists: resort.10
All RabbitMQ connection checks passed.

Process finished with exit code 0
```

If it fails again, send the **latest run output**.

> RABBITMQ_HOST=127.0.0.1;RABBITMQ_PORT=5673;RABBITMQ_USERNAME=skitrak;RABBITMQ_PASSWORD="xxx,RABBITMQ_VHOST=/";RABBITMQ_CHANNEL_POOL_SIZE=16
> Did I enter the environment variables incorrectly?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b0a1552d-f8dd-4470-853a-b93b03314221.png`

**Yes. The password and `RABBITMQ_VHOST` were entered as a single variable value.** The problematic part is:

```text
RABBITMQ_PASSWORD="xxx,RABBITMQ_VHOST=/"
```

The comma didn't separate the variables. `RABBITMQ_VHOST=/` became part of the password, leading to:

```text
AuthenticationFailureException
```

This means **RabbitMQ authentication failed**. The program reached RabbitMQ, but the credentials it sent were incorrect.

1. Open **Run → Edit Configurations**.
2. Select **RabbitMqConnectionCheck**.
3. Click the edit icon next to **Environment variables**.
4. Enter six separate rows:

   | Name | Value |
   |---|---|
   | `RABBITMQ_HOST` | `127.0.0.1` |
   | `RABBITMQ_PORT` | `5673` |
   | `RABBITMQ_USERNAME` | `skitrak` |
   | `RABBITMQ_PASSWORD` | Only your actual new password |
   | `RABBITMQ_VHOST` | `/` |
   | `RABBITMQ_CHANNEL_POOL_SIZE` | `16` |

**Enter the actual password in the Value column without extra wrapping quotes or the placeholder `xxx`.** If a quote is part of your real password, keep it.

Click **OK → Apply → Run** and keep the SSH tunnel open.

The screenshot also shows:

- **SLF4J warning:** A logging implementation is missing. Fix the credentials first and add the logging dependency later.
- **Exit code 130 / SIGINT:** The run was interrupted. A successful run should show all `PASS` messages and exit code `0`.

**Only edit the run configuration for this step.** The Java code and RabbitMQ container can stay as they are.

> I still get an error with these settings. What is causing it? `RABBITMQ_HOST=127.0.0.1;RABBITMQ_PORT=5673;RABBITMQ_USERNAME=skitrak;RABBITMQ_PASSWORD=[the numeric password I entered];RABBITMQ_VHOST="/\"";RABBITMQ_CHANNEL_POOL_SIZE=16`
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e0ebe60b-b17a-412c-8eae-66e33fd6930a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8c731009-a95a-4b5b-a79f-75de3f5009d0.png`

**The screenshot shows a clear error: the value of `RABBITMQ_VHOST` contains an extra double quote.**

The value you entered appears to be:

```text
/"
```

The virtual host we granted access to is named:

```text
/
```

Those are different names. The client treats the extra quote as part of the name.

**Fix the value in the environment variables table:**

1. Double-click the **Value** next to `RABBITMQ_VHOST`.
2. Press `Command + A` and delete the entire value.
3. Enter a single forward slash:

   ```text
   /
   ```

   **Enter the slash by itself, without quotes, backslashes, or spaces.**

4. In the password row, enter your actual new password without wrapping it in extra quotes.
5. Click **OK → Apply → Run** and keep the SSH tunnel terminal open.

The error changed from `AuthenticationFailureException` to `IOException`. **An incorrect virtual host name can cause this connection failure, but the abbreviated log alone can't establish the full cause.** Fix the visible configuration issue first.

Give the run up to about 60 seconds to finish before stopping it. If it still fails, run this in the **EC2 terminal**:

```bash
sudo docker logs --tail 50 skitrak-rabbitmq
```

This shows recent RabbitMQ server logs and helps distinguish a missing virtual host, insufficient permissions, and other issues. Send the latest output, with the password kept out of screenshots.

The three SLF4J messages still indicate a missing logging implementation. That can be addressed separately from the virtual host configuration.

> Is this right now? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d4bbab36-b9b7-4a45-9791-d78eca9e7968.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-55a665c7-9b2c-4cd0-8b53-794dd9b07dd1.png`

**The connection and authentication succeeded, but the full check hasn't passed.** The latest log says:

```text
no queue 'resort.1' in vhost '/'
```

Java reached the correct virtual host, `/`, but **the `resort.1` queue does not exist**. `queueDeclarePassive()` checks whether a queue exists without creating it.

The most likely cause is that **the topology method that creates the queues hasn't been wired into connection initialization**.

### 1. Check the call in the connection manager

Open:

```text
server-v2/src/main/java/messaging/RabbitMqConnectionManager.java
```

Find `maintainResources()` and confirm that these three steps appear in order:

```java
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            // Declare topology before making the connection and pool available.
            RabbitMqTopology.declare(candidateConnection);

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);
```

**Add `RabbitMqTopology.declare(candidateConnection);` if the middle line is missing.**

The steps do the following:

1. Open the connection.
2. Create or verify the exchange, ten queues, and bindings.
3. Create the channel pool.

This way, the manager reports that the resources are ready only after topology initialization finishes.

### 2. Save and rerun

Keep the SSH tunnel open and run **`RabbitMqConnectionCheck` → Run** again.

Leave the account, password, environment variables, and container as they are.

Expected output includes:

```text
PASS: connection and channel pool are ready.
PASS: queue exists: resort.1
...
PASS: queue exists: resort.10
All RabbitMQ connection checks passed.
```

The final line should be:

```text
Process finished with exit code 0
```

### 3. If the line is already there

Leave the check program as it is and verify the existing queues before creating any manually. Run this in the **EC2 terminal**:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_queues -p / name messages
```

This lists the queues that actually exist in `/`. Send me:

- The queue list.
- The section of `maintainResources()` from `newConnection` to `new ChannelPool`.
- The queue declaration section of `RabbitMqTopology.java`.

Set the SLF4J messages aside for now and focus on getting queue initialization to run.

> I added it and ran the check again, but I still get an error. Can I click the green triangle to run it, or do I have to run it from Run → Edit Configurations?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-514535f0-72ca-42a0-96a0-aeeaf08cb33f.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-49cf2b8f-9017-49e5-8b89-c403c6e5fcb3.png`

**You can click the green triangle in the upper-right corner.** As long as `RabbitMqConnectionCheck` is selected beside it, IntelliJ uses the saved environment variables. You can skip opening Edit Configurations each time.

The screenshots show two issues.

### 1. The topology call is still missing from the first screenshot

The screenshot shows a blank line between `newConnection(...)` and `new ChannelPool(...)`. Confirm that the final code looks like this:

```java
            candidateConnection =
                    factory.newConnection("skitrak-ingest");

            // Create exchanges, queues, and bindings before initializing the pool.
            RabbitMqTopology.declare(candidateConnection);

            candidatePool =
                    new ChannelPool(candidateConnection, poolSize);
```

If you've already added the call elsewhere, check its location before adding it again. It belongs between the two operations shown above. Save the file.

### 2. The latest error is now `ConnectException`

**The connection itself didn't open.** You're connecting to `127.0.0.1:5673` on your Mac, so check whether the SSH tunnel has disconnected first.

1. Find the Mac terminal window that was running the SSH tunnel.
2. If `zixin@Mac ~ %` has returned, the tunnel has stopped.
3. If the command is still occupying the window and you're unsure whether it's working, press `Control + C` **in that tunnel window** and rerun the full command:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

If there's no error and the command prompt doesn't return, keep the window open.

4. Make sure **`RabbitMqConnectionCheck`** is selected in the upper-right corner of IntelliJ.
5. Click the **green triangle** beside it and wait for the result.

If `ConnectException` persists, send me the **new tunnel terminal output** and the Host and Port fields from the run configuration. Keep the password private. Leave the account, container, and security group as they are for now.

> Did I add it incorrectly?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e4c7b049-4b3d-4191-bdd1-3b226f83ff6b.png`

**The location and the code are both correct this time. Leave this section as it is.**

The order is now:

```text
Open the connection → Declare the exchange and queues → Create the channel pool
```

The `ConnectException` below means the connection hasn't opened. **Execution hasn't reached the new topology declaration yet.**

Restore the SSH tunnel first:

1. Find the **Mac terminal window** dedicated to the tunnel.
2. Press `Control + C` to stop the old tunnel.
3. Run the full command:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.245.54.122
```

4. If there's no error and the prompt doesn't return, keep the window open.
5. Save the Java file and click the **green triangle next to `RabbitMqConnectionCheck`** in the upper-right corner of IntelliJ to rerun it.

If it still fails, send me the **latest output from the tunnel terminal**. Leave the Java code as it is for now.

> What's happening? I ran the command and nothing appeared.
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-74dbb8a9-e0bf-4e1e-9266-8321cb4577fd.png`

**That's expected.** The `-N` option opens only the SSH tunnel, without a remote shell. It usually produces no output and doesn't return to the prompt.

The screenshot shows that you stopped the first tunnel with `Control + C` and then started it again. **Keep this window open and leave the tunnel running.**

1. Return to IntelliJ.
2. Make sure **`RabbitMqConnectionCheck`** is selected in the upper-right corner.
3. Click the green triangle beside it.
4. Give the run up to about 60 seconds to finish.

The lack of output only means the tunnel hasn't reported an error so far. **The Java check will tell you whether it can actually connect.** Send me the new run output.

> My EC2 session dropped. I reopened it and started the instance again. What should I do now? Running the check again still gives an error.
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-1aeb8163-e851-4740-bb5e-9f187de50707.png`

**Leave the Java code as it is for now.** The `ConnectException` means a connection hasn't opened. Stopping and restarting EC2 can change its **public IP**, and you'll need to recreate the SSH tunnel.

**1. Find the RabbitMQ instance's current public IP**

Go to **AWS → EC2 → Instances**, select **`cs6650-a2-rabbitmq`**, and confirm that:

- Its state is `Running`.
- Its status checks have passed.
- **Details** shows its **Public IPv4 address**.

Reuse `54.245.54.122` only if the console confirms that it's still the current address.

**2. Reconnect and check the container**

Run this in Terminal on your Mac, replacing the placeholder with the current public IP:

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@CURRENT_PUBLIC_IP
```

Once logged in, run:

```bash
sudo docker ps -a
```

If `skitrak-rabbitmq` is `Up`, check it with:

```bash
sudo docker exec skitrak-rabbitmq rabbitmq-diagnostics -q ping
```

If the container is `Exited`, start it before running the check:

```bash
sudo docker start skitrak-rabbitmq
```

**Reuse the existing container, user account, and volume.**

**3. Recreate the SSH tunnel**

Press `Control + C` in the old tunnel window. Then run the following **on your Mac**, using the same current public IP:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@CURRENT_PUBLIC_IP
```

Keep the window open. No output is expected. If an error appears, resolve it before running Java.

**4. Run the IntelliJ check again**

Keep the environment variables set to:

```text
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5673
```

**Use localhost here**, because Java connects to the local tunnel endpoint.

Click the green triangle next to `RabbitMqConnectionCheck`.

Send me the **current public IP of RabbitMQ** and the **`docker ps -a` output**. I can then give you the exact commands with the address filled in.

> Why does screenshot 1 say “No such file or directory”? Do I only need to start one instance?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-cc757bfa-b178-4551-a307-0eca280df8ae.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e257cd5e-39e8-4204-9afc-84cbb6c79ad6.png`

**You ran the tunnel command on EC2, but it needs to run on your Mac.** The private key is under `/Users/zixin/.ssh/` on your Mac. That path doesn't exist on EC2. The file itself is still on your Mac.

The screenshots also confirm:

- RabbitMQ's new public IP is **`54.69.65.232`**.
- `Ping succeeded` means RabbitMQ is responding. You can use the existing installation.

**For this IntelliJ connection check, only the RabbitMQ instance needs to be running.** You can leave the original ingest server stopped until you deploy the HTTP service and run end-to-end tests.

**1. Cancel the current command**

In the current window, press:

```text
Control + C
```

**2. Open a new Mac terminal window**

Press **`Command + N`** and confirm the new window shows:

```text
zixin@Mac ~ %
```

The prompt `[ec2-user@ip-… ~]$` indicates a remote EC2 session.

**3. Run the full tunnel command in the new Mac window**

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.69.65.232
```

If there is no error and the prompt doesn't return, keep the window open.

**4. Rerun the check in IntelliJ**

The environment variables stay the same:

```text
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5673
```

Click the **green triangle next to `RabbitMqConnectionCheck`** and wait for it to finish.

Keep two terminal windows open:

| Window | Purpose |
|---|---|
| Shows `[ec2-user@ip-…]$` | Manage Docker and RabbitMQ on EC2 |
| Runs `ssh -N ...` without a prompt | Keep the tunnel from your Mac to RabbitMQ open. Use another window for other commands |

> Is this right now? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-af770f40-582e-40ba-9e88-05d4ef85648b.png`

**Yes, all the checks passed.** The screenshot shows `PASS` for all ten queues and exit code `0`.

This confirms that Java authenticated, connected, initialized the channel pool, and declared the queues. **Next, test the assignment's automatic recovery requirement.** Keep the Java program running, stop and restart RabbitMQ, and see whether the program reconnects on its own.

Leave EC2 running so the public IP won't change as a result of this test.

### 1. Create the recovery check

In IntelliJ, go to **`server-v2 → src → test → java → messaging`**, right-click, choose **New → Java Class**, and create:

```text
RabbitMqRecoveryCheck
```

This file checks recovery without changing the production connection management logic. Enter:

```java
package messaging;

import com.rabbitmq.client.Channel;

import java.util.concurrent.TimeUnit;

public final class RabbitMqRecoveryCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            manager.start();
            awaitState(manager, true, 60);

            ChannelPool originalPool = manager.getChannelPool();

            System.out.println("PASS: initial connection is ready.");
            System.out.println("ACTION: stop the RabbitMQ container now.");

            // Require an observed outage before testing recovery.
            awaitState(manager, false, 120);

            System.out.println("PASS: connection loss was detected.");
            System.out.println("ACTION: start the RabbitMQ container now.");

            // Keep the same manager and wait for automatic recovery.
            awaitState(manager, true, 120);

            ChannelPool recoveredPool = manager.getChannelPool();

            if (recoveredPool == originalPool) {
                throw new AssertionError(
                        "Recovery did not replace the channel pool"
                );
            }

            System.out.println("PASS: a replacement channel pool is ready.");

            Channel channel = recoveredPool.borrowChannel();

            try {
                // Verify that the recovered resources can perform broker operations.
                for (int resortID = 1; resortID <= 10; resortID++) {
                    String queueName =
                            RabbitMqTopology.routingKey(resortID);

                    channel.queueDeclarePassive(queueName);
                }
            } finally {
                recoveredPool.returnChannel(channel);
            }

            System.out.println("PASS: all 10 queues are accessible after recovery.");
            System.out.println("All RabbitMQ recovery checks passed.");
        }
    }

    // Wait for the requested readiness state with a bounded timeout.
    private static void awaitState(
            RabbitMqConnectionManager manager,
            boolean expectedConnected,
            int timeoutSeconds) throws InterruptedException {

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while (manager.isConnected() != expectedConnected) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for connected="
                                + expectedConnected
                );
            }

            Thread.sleep(200);
        }
    }
}
```

### 2. What the code does

| Code section | Purpose |
|---|---|
| Start and wait for the first connection | Confirm that the service works at the start of the test |
| Save `originalPool` | Record the channel pool object before the failure |
| Wait for the connection state to become `false` | Observe an actual disconnect so the check can't pass without one |
| Wait for the state to return to `true` | Verify that the same manager recovers without restarting Java |
| Compare the old and new pools | Verify that recovery replaced the pool and its old channels |
| Borrow a channel and check all ten queues | Confirm that the recovered connection can perform real operations |
| `awaitState()` | Poll every 200 milliseconds with a time limit |
| `try` and `finally` | Return the channel and clean up manager resources at the end |

The object comparison:

```java
recoveredPool == originalPool
```

checks whether the two references point to the same pool object. It doesn't compare their settings.

**This check verifies recovery of the connection and channel pool.** Testing for lost or duplicated in-flight messages requires the publisher and consumer implementations.

### 3. Configure the check

Reuse the environment variables you've already set:

1. Open **Run → Edit Configurations**.
2. Select **`RabbitMqConnectionCheck`** on the left.
3. Click **Copy Configuration** in the upper left. The icon usually looks like two overlapping sheets. Hover over it to confirm.
4. Set the copy's **Name** to:

   ```text
   RabbitMqRecoveryCheck
   ```

5. Set **Main class** to:

   ```text
   messaging.RabbitMqRecoveryCheck
   ```

6. Keep the six copied environment variables.
7. Click **Apply → OK**.

This saves you from typing the password again.

### 4. Run the failure test in order

Keep these windows open:

- **Tunnel terminal:** The window running `ssh -N ...`.
- **EC2 management terminal:** The window with the prompt `[ec2-user@ip-…]$`, where you'll run Docker commands.

**① Run `RabbitMqRecoveryCheck` in IntelliJ**

Wait until you see:

```text
PASS: initial connection is ready.
ACTION: stop the RabbitMQ container now.
```

Then **leave the Java program running**.

**② Stop RabbitMQ in the EC2 management terminal**

```bash
sudo docker stop skitrak-rabbitmq
```

Stop only the container and leave the EC2 instance and SSH tunnel running.

**③ Wait for IntelliJ to show that it detected the disconnection**

Expect:

```text
PASS: connection loss was detected.
ACTION: start the RabbitMQ container now.
```

Warnings about initialization or recovery failures are expected while RabbitMQ is unavailable.

**④ Restart the container in the EC2 management terminal**

```bash
sudo docker start skitrak-rabbitmq
```

Wait for RabbitMQ to start. Java should recover on its own without another click on Run.

**⑤ Check the final output**

Expect:

```text
PASS: a replacement channel pool is ready.
PASS: all 10 queues are accessible after recovery.
All RabbitMQ recovery checks passed.
```

and:

```text
Process finished with exit code 0
```

Save a screenshot of this result for the connection recovery section of your report. Once it passes, we'll write **`SwipePublisher`** to actually publish swipe events to the queues.

> Is this right?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-a153c326-7b1b-46ec-83a7-26d3a1b9cd9b.png`

**Yes, the recovery check passed with exit code `0`.**

The screenshot verifies the full sequence:

1. The initial connection and channel pool worked.
2. Java detected the connection failure after you stopped RabbitMQ.
3. Background retries continued without a Java restart.
4. Once RabbitMQ started again, Java opened a new connection and built a new channel pool.
5. The recovered connection could still access all ten queues.

The three `WARNING` lines are expected retry messages from the period when RabbitMQ was unavailable. The test recovered as expected. The SLF4J message at the top concerns a separate logging configuration issue and didn't affect this check.

**Save this screenshot** as evidence for the report that the application recovered automatically after a RabbitMQ outage. It verifies recovery of the connection and pool. Whether any messages are lost or duplicated remains to be tested.

Next, implement **`SwipePublisher.java`**. It will convert `SwipeMessage` to JSON, borrow a channel from the pool, publish to the appropriate resort, and check RabbitMQ's publisher confirmation. After that works, integrate it with `SkierServlet`.

> When you ask me to save a screenshot, please also tell me the filename so I know which file to insert where when writing the report.
> Please tell me what to do next.

<details><summary>2 previous messages</summary>

> Whenever you need to save evidence, I'll give you the filename, where to save it, and where it belongs in the report. This time we'll write `SwipePublisher` to handle serialization, routing, publisher confirms, and channel cleanup after failures. I'll first check how RabbitMQ handles confirmations and returned messages.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/docs/confirms`
>
> Searched the web for `https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/main/src/main/java/com/rabbitmq/client/impl/ChannelN.java`
>
> </details>
</details>

Okay. For every screenshot you need to save, I'll specify the **filename, directory, and where to insert it in the report**.

Save the recovery test screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/01-rabbitmq-recovery-check-pass.png
```

In **Test Results → Failure Recovery Verification**, use it to show that the application detected the disconnect when RabbitMQ stopped, automatically rebuilt the connection and channel pool, and could access all ten queues after recovery. This test doesn't establish that no messages were lost.

Next, implement **`SwipePublisher.java`** so the application can publish swipe messages.

### 1. What the file does

```text
SwipeMessage
    ↓ Convert to JSON
Borrow a Channel from the pool
    ↓
Publish to skitrak.exchange
    ↓
Check routing and publisher confirmation
    ↓
Return the Channel
```

HTTP request validation and leaderboard calculation happen in other components.

### 2. Create the file

In IntelliJ, **right-click `server-v2/src/main/java/messaging` → New → Java Class**. Enter:

```text
SwipePublisher
```

Paste the following code. The comments are in English:

```java
package messaging;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ReturnListener;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class SwipePublisher {

    private static final Logger LOGGER =
            Logger.getLogger(SwipePublisher.class.getName());

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitMqConnectionManager manager;
    private final Gson gson = new Gson();

    // Reuse the application's shared connection manager.
    public SwipePublisher(RabbitMqConnectionManager manager) {
        this.manager = Objects.requireNonNull(
                manager, "manager must not be null"
        );
    }

    // Return normally only after a routed publish is confirmed.
    public void publish(SwipeMessage message)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(message, "message must not be null");

        int resortID = Integer.parseInt(message.getResortID());
        String routingKey = RabbitMqTopology.routingKey(resortID);

        byte[] body = gson.toJson(message)
                .getBytes(StandardCharsets.UTF_8);

        AMQP.BasicProperties properties =
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("UTF-8")
                        .deliveryMode(2)
                        .messageId(message.getEventId())
                        .build();

        // Keep this pool reference even if recovery installs a new pool.
        ChannelPool pool = manager.getChannelPool();
        Channel channel = pool.borrowChannel();

        AtomicReference<String> returnedReason = new AtomicReference<>();

        ReturnListener listener =
                (replyCode, replyText, exchange, key, props, returnedBody) ->
                        returnedReason.set(replyCode + " " + replyText);

        boolean successful = false;

        try {
            // Detect messages that cannot be routed to any queue.
            channel.addReturnListener(listener);

            channel.basicPublish(
                    RabbitMqTopology.SWIPE_EXCHANGE,
                    routingKey,
                    true,
                    properties,
                    body
            );

            // Channels enter confirm mode during pool initialization.
            channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);

            if (returnedReason.get() != null) {
                throw new IOException(
                        "RabbitMQ returned an unroutable message: "
                                + returnedReason.get()
                );
            }

            successful = true;

        } finally {
            // Do not reuse a channel with a failed or uncertain publish.
            if (!successful) {
                abortQuietly(channel);
            }

            channel.removeReturnListener(listener);
            pool.returnChannel(channel);
        }
    }

    // Preserve the original publish failure if channel cleanup also fails.
    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Could not abort a publishing channel");
        }
    }
}
```

### 3. What each section does

**① Store the manager and reuse its connection**

```java
private final RabbitMqConnectionManager manager;
```

The constructor receives the application's existing manager. Multiple requests can share one publisher, and the channel pool coordinates channel use. Publishing doesn't call `newConnection()`.

**② Determine the routing key**

```java
int resortID = Integer.parseInt(message.getResortID());
String routingKey = RabbitMqTopology.routingKey(resortID);
```

Convert the message's resort ID to an integer and apply the existing routing rule:

```text
Resort 3 → resort.3
```

A nonnumeric ID or an ID outside 1–10 raises an argument error before publishing.

**③ Serialize the message**

```java
byte[] body = gson.toJson(message)
        .getBytes(StandardCharsets.UTF_8);
```

Gson converts the `SwipeMessage` to JSON. UTF-8 encoding turns the JSON into the byte array the RabbitMQ client sends. The publisher keeps the original `eventId` so retries of the same event can reuse it.

**④ Set message properties**

| Property | Meaning |
|---|---|
| `contentType` | The payload is JSON |
| `contentEncoding` | The payload uses UTF-8 |
| `deliveryMode(2)` | Marks the message as persistent |
| `messageId` | Also stores the event ID in the message properties |

The consumer still needs to deduplicate by `eventId` to make aggregation idempotent.

**⑤ Borrow and return through the same pool**

```java
ChannelPool pool = manager.getChannelPool();
Channel channel = pool.borrowChannel();
```

Save the pool reference before borrowing a channel. If recovery installs a new pool mid-publish, return the old channel to its original pool. Looking up the pool again in `finally` could return the channel to the wrong pool.

**⑥ Listen for unroutable messages**

```java
AtomicReference<String> returnedReason
```

Record RabbitMQ's reason for returning a message. The callback may run on a different thread from the publisher, so use an `AtomicReference` to share the result safely. The `ReturnListener` only records the reason. It leaves retries and channel operations to the publishing code.

**⑦ Publish with `basicPublish()`**

The five arguments are:

```text
Exchange name
Routing key
Whether routing is mandatory
Message properties
Message body
```

The argument:

```java
true
```

sets `mandatory=true`, which asks RabbitMQ to return the message if it can't route it to any queue.

**Check for a returned message as well as a publisher confirm.** RabbitMQ can confirm a message that didn't route to any queue. For a mandatory publish, RabbitMQ sends the return before the corresponding confirm. [RabbitMQ confirmation rules](https://www.rabbitmq.com/docs/confirms)

**⑧ Wait for publisher confirmation**

```java
channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
```

The pool already called `confirmSelect()` during initialization. Here:

- A positive confirmation allows the code to continue.
- A negative confirmation or connection failure throws an exception.
- Waiting more than five seconds throws a timeout exception.

**The five seconds limit the confirm wait.** The entire HTTP request can take longer.

A positive confirm means the broker accepted responsibility for the message under the relevant delivery guarantees. The consumer may still have to update the leaderboard. [Java API guide](https://rabbitmq.github.io/rabbitmq-java-client/api/current/com/rabbitmq/client/Channel.html)

**⑨ Return or retire the channel in `finally`**

Only set the following after confirmation succeeds and the message wasn't returned:

```java
successful = true;
```

Otherwise, close the channel before returning it. The pool keeps closed channels out of the available queue, and the current recovery strategy subsequently rebuilds the resources. Remove the listener so it doesn't receive callbacks from the next use of the channel.

**⑩ Why does the publisher leave retries to the caller?**

On a confirm timeout, RabbitMQ may have received the message even though the confirmation didn't arrive. Resending it as a new event increases the risk of counting it twice.

This version propagates the exception to the caller. Reliable handling will combine:

- The same `eventId` for each retry of one logical swipe.
- HTTP request retries.
- Consumer deduplication.

### 4. Verify by compiling

Save and run **Maven → ski-servlet → Lifecycle → compile**. Expect:

```text
BUILD SUCCESS
```

`publish()` hasn't been called yet, so this build sends no messages. A screenshot of this routine build isn't needed. Next, we'll send a known swipe event and check the actual queued message. I'll give you the screenshot filename and report location then.

> The build succeeded. What should I do next?

<details><summary>2 previous messages</summary>

> Next, run a real publishing check. Send one known swipe event, then read it from the target queue and verify its contents, event ID, and persistence flag. The check removes the test message it sends, so run it before starting consumers and while no other client is sending data. The code also checks that the queue is empty first.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/docs/confirms`
>
> </details>
</details>

Next, verify that **`SwipePublisher` sends the correct message to the correct queue**.

Add only a test file in this step. The production code can stay as it is. The check follows this flow:

```text
Construct one swipe event
    ↓
Publish through SwipePublisher
    ↓
Read the message from the target queue
    ↓
Verify its contents and properties
    ↓
Acknowledge the test message
```

**Keep the RabbitMQ instance and SSH tunnel running, and leave other swipe clients and consumers stopped.**

### 1. Create the check class

In IntelliJ, right-click **`server-v2 → src → test → java → messaging`**, choose **New → Java Class**, and enter:

```text
SwipePublisherCheck
```

Paste:

```java
package messaging;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import model.LiftVerticalLookup;
import model.SwipeMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class SwipePublisherCheck {

    public static void main(String[] args) throws Exception {
        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();
        Gson gson = new Gson();
        String queueName = RabbitMqTopology.routingKey(1);

        try (RabbitMqConnectionManager manager =
                     new RabbitMqConnectionManager(config)) {

            manager.start();

            long deadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

            while (!manager.isConnected()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "RabbitMQ did not become ready within 60 seconds"
                    );
                }

                Thread.sleep(200);
            }

            // Require an idle, empty queue before this controlled check.
            ChannelPool initialPool = manager.getChannelPool();
            Channel initialChannel = initialPool.borrowChannel();

            try {
                AMQP.Queue.DeclareOk state =
                        initialChannel.queueDeclarePassive(queueName);

                if (state.getMessageCount() != 0
                        || state.getConsumerCount() != 0) {

                    throw new IllegalStateException(
                            "This check requires an empty resort.1 queue "
                                    + "with no active consumers"
                    );
                }
            } finally {
                initialPool.returnChannel(initialChannel);
            }

            // Construct a known event using the supplied lift table.
            SwipeMessage message = new SwipeMessage(
                    UUID.randomUUID().toString(),
                    "1",
                    "1",
                    "1001",
                    "1",
                    LiftVerticalLookup.getVertical(1),
                    120,
                    Instant.now().toString(),
                    "publisher-check"
            );

            SwipePublisher publisher = new SwipePublisher(manager);
            publisher.publish(message);

            System.out.println("PASS: publisher received broker confirmation.");

            ChannelPool readPool = manager.getChannelPool();
            Channel readChannel = readPool.borrowChannel();

            try {
                // Read without automatic acknowledgement.
                GetResponse received =
                        readChannel.basicGet(queueName, false);

                if (received == null) {
                    throw new AssertionError(
                            "No message was found in resort.1"
                    );
                }

                long deliveryTag =
                        received.getEnvelope().getDeliveryTag();

                String payload = new String(
                        received.getBody(), StandardCharsets.UTF_8
                );

                try {
                    // Compare JSON structures rather than property order.
                    if (!gson.toJsonTree(message).equals(
                            JsonParser.parseString(payload))) {

                        throw new AssertionError(
                                "Received JSON does not match the sent event"
                        );
                    }

                    if (!message.getEventId().equals(
                            received.getProps().getMessageId())) {

                        throw new AssertionError(
                                "Message ID does not match eventId"
                        );
                    }

                    if (!Integer.valueOf(2).equals(
                            received.getProps().getDeliveryMode())) {

                        throw new AssertionError(
                                "Message is not marked persistent"
                        );
                    }

                    if (!"application/json".equals(
                            received.getProps().getContentType())) {

                        throw new AssertionError(
                                "Unexpected message content type"
                        );
                    }

                } catch (RuntimeException | AssertionError e) {
                    // Preserve unexpected data instead of deleting it.
                    readChannel.basicNack(deliveryTag, false, true);
                    throw e;
                }

                // Remove the verified test message from the queue.
                readChannel.basicAck(deliveryTag, false);

                System.out.println("PASS: event arrived in resort.1.");
                System.out.println("PASS: complete JSON and message ID match.");
                System.out.println("PASS: persistent JSON properties are correct.");
                System.out.println("Received JSON: " + payload);

            } finally {
                readPool.returnChannel(readChannel);
            }

            System.out.println("All swipe publisher checks passed.");
        }
    }
}
```

### 2. What each section does

**① Wait for the connection to be ready**

As in the earlier connection check, start the existing manager and wait for the connection, topology, and channel pool to be ready. The check exercises the real components without building another connection path.

**② Verify that the queue is empty and has no consumers**

```java
state.getMessageCount()
state.getConsumerCount()
```

Check the number of ready messages and registered consumers. This reduces the chance of reading other data or having another program consume the test message. The check doesn't lock the queue, so **keep other programs from using it concurrently**. If the queue isn't empty, stop and send me the result. Keep the queued data for diagnosis.

**③ Construct a known swipe event**

| Field | Value |
|---|---|
| Resort | `1` |
| Day ID | `1` |
| Skier | `1001` |
| Lift | `1` |
| Vertical feet | `167`, looked up in the official table |
| Business time | `120` |
| Event ID | A new UUID |
| Message timestamp | The current time |
| Ingest server ID | `publisher-check` |

`publisher-check` identifies this test. The HTTP ingest service will later use its own instance ID.

**④ Call the real publisher**

```java
publisher.publish(message);
```

This serializes the message to JSON, borrows and returns a channel, routes the publish, checks for a returned message, and checks the publisher confirmation. It reports that confirmation passed only after the call returns normally.

**⑤ Read from the queue with acknowledgment pending**

```java
readChannel.basicGet(queueName, false);
```

`false` disables automatic acknowledgment. Read the message, verify it, and then decide whether to acknowledge it. This check reads a single message temporarily. The final consumer pool is implemented separately.

**⑥ Compare the full JSON and message properties**

```java
gson.toJsonTree(message).equals(
        JsonParser.parseString(payload))
```

Compare JSON structures independently of field order. Also verify that the message property's ID matches the event ID, the persistence flag is `2`, and the content type is JSON. This verifies the publishing property. Testing whether messages survive a restart is a separate check.

**⑦ Acknowledge after verification**

```java
readChannel.basicAck(deliveryTag, false);
```

This tells RabbitMQ the message has been processed and can be removed. If verification fails, call:

```java
readChannel.basicNack(deliveryTag, false, true);
```

The final `true` requeues the message so you can investigate it. Send the acknowledgment on the same Channel that received the message. [RabbitMQ acknowledgment guide](https://www.rabbitmq.com/docs/confirms)

### 3. Create a run configuration

1. Open **Run → Edit Configurations**.
2. Select `RabbitMqConnectionCheck`.
3. Click **Copy Configuration**.
4. Set **Name** to `SwipePublisherCheck`.
5. Set **Main class** to `messaging.SwipePublisherCheck`.
6. Keep the existing environment variables, including `RABBITMQ_HOST=127.0.0.1` and `RABBITMQ_PORT=5673`.
7. Click **Apply → OK**.
8. Confirm the SSH tunnel is running, then click the green triangle in the upper right.

### 4. Expected results and screenshot filename

Expected output:

```text
PASS: publisher received broker confirmation.
PASS: event arrived in resort.1.
PASS: complete JSON and message ID match.
PASS: persistent JSON properties are correct.
Received JSON: ...
All swipe publisher checks passed.

Process finished with exit code 0
```

After it passes, save the screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/02-swipe-publisher-check-pass.png
```

Insert it under **Test Results → Queue Publishing Correctness**. Explain that the publisher sent a known swipe event into the target queue and that the full JSON, event ID, and persistence flag matched.

Once this passes, we'll integrate the publisher with **the Tomcat lifecycle and `SkierServlet`** so real HTTP requests can go through the same flow.

> Okay, it passed. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-ec41b7a0-c5f3-4544-a825-59a7e4440c36.png`

<details><summary>2 previous messages</summary>

> The publishing check passed. Next, have Tomcat create the shared connection manager and publisher at startup and release resources at shutdown. I'll first review your current `web.xml` and servlet so I can show you exactly where to make the changes. You'll make the edits manually in IntelliJ again.
>
> <details><summary>Ran a command, searched the web</summary>
>
> - Ran `cat '/Users/zixin/Downloads/6650/assignment2/server-v2/web/WEB-INF/web.xml' '/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/SkierServlet.java'`
>
> Searched the web for `https://tomcat.apache.org/tomcat-9.0-doc/servletapi/javax/servlet/ServletContextListener.html`
>
> </details>
</details>

**It passed.** The screenshot shows that the message reached `resort.1`, the complete JSON, event ID, and persistence properties matched, and the process exited with code `0`.

Save the screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/02-swipe-publisher-check-pass.png
```

Insert it under **Test Results → Queue Publishing Correctness**.

Next, have **Tomcat create the connection manager and publisher automatically at application startup**. The servlet can then reuse these components across requests.

### 1. Files to change

| File | Change | Purpose |
|---|---|---|
| `ApplicationLifecycleListener.java` | Create | Initialize shared components at startup and close resources at shutdown |
| `web/WEB-INF/web.xml` | Edit | Register the listener with Tomcat |

We'll set up lifecycle management first and edit `SkierServlet` in the next round so you can verify each step.

Target structure:

```text
Tomcat 启动 Web 应用
    ↓
ApplicationLifecycleListener
    ├─ 读取配置与落差表
    ├─ 创建一个连接管理器
    ├─ 创建一个发布器
    └─ 保存到 ServletContext
            ↓
后续 SkierServlet 从中获取发布器
```

### 2. Create the lifecycle listener

In IntelliJ, **right-click `server-v2/src/main/java/messaging` → New → Java Class**. Enter:

```text
ApplicationLifecycleListener
```

Paste:

```java
package messaging;

import model.LiftVerticalLookup;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.UUID;
import java.util.logging.Logger;

public final class ApplicationLifecycleListener
        implements ServletContextListener {

    private static final Logger LOGGER =
            Logger.getLogger(ApplicationLifecycleListener.class.getName());

    // Use shared attribute names when storing and retrieving components.
    public static final String MANAGER_ATTRIBUTE =
            RabbitMqConnectionManager.class.getName();

    public static final String PUBLISHER_ATTRIBUTE =
            SwipePublisher.class.getName();

    public static final String SERVER_ID_ATTRIBUTE =
            ApplicationLifecycleListener.class.getName() + ".serverId";

    private RabbitMqConnectionManager manager;

    // Initialize shared components once per web application startup.
    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();

        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();

        // Fail initialization early if the bundled lift table is invalid.
        LiftVerticalLookup.getVertical(1);

        String serverId = System.getenv("SERVER_ID");

        if (serverId == null || serverId.isBlank()) {
            serverId = "ingest-" + UUID.randomUUID();
        }

        manager = new RabbitMqConnectionManager(config);

        try {
            SwipePublisher publisher = new SwipePublisher(manager);

            context.setAttribute(MANAGER_ATTRIBUTE, manager);
            context.setAttribute(PUBLISHER_ATTRIBUTE, publisher);
            context.setAttribute(SERVER_ID_ATTRIBUTE, serverId);

            // Start initialization and recovery on the background worker.
            manager.start();

            LOGGER.info(
                    "Messaging initialization scheduled for server " + serverId
            );

        } catch (RuntimeException e) {
            removeAttributes(context);
            manager.close();
            throw e;
        }
    }

    // Release resources when the application stops or is redeployed.
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        removeAttributes(event.getServletContext());

        if (manager != null) {
            manager.close();
        }

        LOGGER.info("Messaging resources closed");
    }

    // Remove references to components owned by this application.
    private static void removeAttributes(ServletContext context) {
        context.removeAttribute(PUBLISHER_ATTRIBUTE);
        context.removeAttribute(MANAGER_ATTRIBUTE);
        context.removeAttribute(SERVER_ID_ATTRIBUTE);
    }
}
```

### 3. What each section does

**① `implements ServletContextListener` receives lifecycle notifications**

At web application startup, Tomcat calls:

```java
contextInitialized(...)
```

At shutdown or redeployment, it calls:

```java
contextDestroyed(...)
```

These notifications apply to the **web application lifecycle**. Initialization happens before servlet initialization. [Tomcat documentation](https://tomcat.apache.org/tomcat-9.0-doc/servletapi/javax/servlet/ServletContextListener.html)

**② Three attribute names provide consistent keys for the shared components**

```java
MANAGER_ATTRIBUTE
PUBLISHER_ATTRIBUTE
SERVER_ID_ATTRIBUTE
```

The names work like keys in a map. For example:

```java
context.setAttribute(PUBLISHER_ATTRIBUTE, publisher);
```

This stores the publisher in the application context. The servlet retrieves **the same publisher object** with the same key.

`ServletContext` is shared within one web application. If you deploy four ingest servers, each has its own objects and connection manager.

**③ Load the vertical-drop table early**

```java
LiftVerticalLookup.getVertical(1);
```

The first call loads and validates the entire table. The return value, `167`, is incidental. Loading early exposes a missing or malformed resource during application startup, before the first swipe request.

**④ Read or generate the server ID**

Prefer the environment variable:

```text
SERVER_ID
```

For example, you can set the following on different instances:

```text
ingest-1
ingest-2
ingest-3
ingest-4
```

If it is unset, use `ingest-` followed by a random UUID. The fallback ID stays the same for one application run and changes after a restart. It supplies `serverId` in future swipe messages.

**⑤ Create the shared manager and publisher**

```java
manager = new RabbitMqConnectionManager(config);
SwipePublisher publisher = new SwipePublisher(manager);
```

Create each one once at startup and store it for reuse. Putting these operations in the servlet's `doPost()` could create a fresh set of resources on each request.

**⑥ Starting initialization is separate from readiness**

```java
manager.start();
```

The method returns immediately after starting the background connection process. That's why the log says:

```text
Messaging initialization scheduled ...
```

At that point, RabbitMQ might still be unavailable and the background task will continue retrying. The servlet must handle unavailability and return a failure when publishing can't be confirmed. We'll also adapt the health check later.

**⑦ Cleanup and exception handling**

- If a later step in initialization fails, remove the shared references, close the manager, and report the error.
- On application shutdown, remove the references and close the manager.
- The manager closes the channel pool and connection and stops the recovery task.

Removing attributes drops the references. `manager.close()` releases the resources.

### 4. Register the listener in `web.xml`

Open:

```text
server-v2/web/WEB-INF/web.xml
```

Find:

```xml
    <display-name>Ski Resort API</display-name>
```

Add this after it:

```xml
    <!-- Initialize and close shared messaging resources. -->
    <listener>
        <listener-class>messaging.ApplicationLifecycleListener</listener-class>
    </listener>
```

Keep the other entries, especially the existing:

```xml
    <listener>
        <listener-class>LeaderboardEndpoint</listener-class>
    </listener>
```

The original listener still handles simulated leaderboards. We'll revisit it when we connect the real broadcast mechanism.

**Register the new listener only once.** The `web.xml` entry is sufficient, so leave off the `@WebListener` annotation.

### 5. Verify the change

Save both files and run **Maven → ski-servlet → Lifecycle → package**.

`package` compiles the code, processes the web configuration, and attempts to build a deployable WAR. It won't automatically run the standalone checks with `main()` methods.

Expected output:

```text
BUILD SUCCESS
```

No screenshot is needed for this build. **We'll verify that the listener runs in Tomcat when we deploy and test an HTTP request after connecting the servlet.**

The environment variables in the IntelliJ check apply only to that run configuration. We'll configure Tomcat on EC2 separately during deployment, using RabbitMQ's private IP `172.31.45.116` and port `5672`.

> The build succeeded. What should I do next?

Next, **integrate `SwipePublisher` into `SkierServlet`** so real HTTP swipe requests reach RabbitMQ.

The completed flow will be:

```text
HTTP swipe request → SkierServlet validates it and creates an event → SwipePublisher publishes it → Return 201 after confirmation
```

**Edit only `SkierServlet.java` in this step. Deployment comes later.**

### 1. Define the event ID contract

Add this request header so retries still represent the same swipe:

```text
X-Event-ID: 一个 UUID
```

The client must use **the same ID** when retrying one logical swipe and generate a new ID for the next swipe.

This version requires the header and returns `400` if it's missing. We'll adapt the original client later. Generating a new ID on the server for every retry would prevent the consumer from recognizing duplicates.

### 2. Replace the servlet file

Open:

```text
server-v2/src/main/java/SkierServlet.java
```

**Replace the entire file with the code below.** It remains in the default package to match the class name in the original `web.xml`.

```java
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import messaging.ApplicationLifecycleListener;
import messaging.RabbitMqConnectionManager;
import messaging.SwipePublisher;
import model.LiftRide;
import model.LiftVerticalLookup;
import model.ResponseMsg;
import model.SwipeMessage;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public class SkierServlet extends HttpServlet {

    private static final Logger LOGGER =
            Logger.getLogger(SkierServlet.class.getName());

    private final Gson gson = new Gson();

    private RabbitMqConnectionManager manager;
    private SwipePublisher publisher;
    private String serverId;

    // Retrieve the components created by the application listener.
    @Override
    public void init() throws ServletException {
        ServletContext context = getServletContext();

        manager = (RabbitMqConnectionManager) context.getAttribute(
                ApplicationLifecycleListener.MANAGER_ATTRIBUTE
        );

        publisher = (SwipePublisher) context.getAttribute(
                ApplicationLifecycleListener.PUBLISHER_ATTRIBUTE
        );

        serverId = (String) context.getAttribute(
                ApplicationLifecycleListener.SERVER_ID_ATTRIBUTE
        );

        if (manager == null || publisher == null || serverId == null) {
            throw new ServletException(
                    "Shared messaging components were not initialized"
            );
        }
    }

    // Keep the existing informational GET endpoint.
    @Override
    protected void doGet(
            HttpServletRequest req,
            HttpServletResponse res) throws IOException {

        String path = req.getPathInfo();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            writeMessage(res, 404, "missing parameters");
            return;
        }

        String[] parts = path.split("/");

        if (!isUrlValid(parts)) {
            writeMessage(res, 400, "invalid url");
            return;
        }

        writeMessage(
                res,
                200,
                String.format(
                        "GET request processed for skier %s at resort %s, "
                                + "season %s, day %s",
                        parts[7], parts[1], parts[3], parts[5]
                )
        );
    }

    // Validate a swipe and acknowledge HTTP success only after publishing.
    @Override
    protected void doPost(
            HttpServletRequest req,
            HttpServletResponse res) throws IOException {

        String path = req.getPathInfo();

        if (path == null || path.isEmpty() || "/".equals(path)) {
            writeMessage(res, 400, "missing parameters");
            return;
        }

        String[] parts = path.split("/");

        if (!isUrlValid(parts)) {
            writeMessage(res, 400, "invalid url");
            return;
        }

        // Require a stable UUID across retries of the same logical event.
        String suppliedId = req.getHeader("X-Event-ID");
        String eventId;

        if (suppliedId == null || suppliedId.isBlank()) {
            writeMessage(res, 400, "missing X-Event-ID header");
            return;
        }

        suppliedId = suppliedId.trim();

        try {
            eventId = UUID.fromString(suppliedId).toString();

            if (!eventId.equalsIgnoreCase(suppliedId)) {
                throw new IllegalArgumentException(
                        "Noncanonical UUID format"
                );
            }
        } catch (IllegalArgumentException e) {
            writeMessage(res, 400, "X-Event-ID must be a valid UUID");
            return;
        }

        LiftRide ride;

        try {
            ride = gson.fromJson(req.getReader(), LiftRide.class);
        } catch (JsonSyntaxException e) {
            writeMessage(res, 400, "invalid JSON body");
            return;
        }

        if (ride == null || !isLiftRideValid(ride)) {
            writeMessage(res, 400, "invalid JSON body");
            return;
        }

        res.setHeader("X-Event-ID", eventId);

        if (!manager.isConnected()) {
            serviceUnavailable(res);
            return;
        }

        // Normalize numeric IDs before placing them in the event.
        String resortID = Integer.toString(Integer.parseInt(parts[1]));
        String dayID = Integer.toString(Integer.parseInt(parts[5]));
        String skierID = Integer.toString(Integer.parseInt(parts[7]));

        SwipeMessage message = new SwipeMessage(
                eventId,
                resortID,
                dayID,
                skierID,
                Integer.toString(ride.getLiftID()),
                LiftVerticalLookup.getVertical(ride.getLiftID()),
                ride.getTime(),
                Instant.now().toString(),
                serverId
        );

        try {
            publisher.publish(message);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Swipe publishing was interrupted");
            serviceUnavailable(res);
            return;

        } catch (IOException | TimeoutException | IllegalStateException e) {
            LOGGER.warning(
                    "Swipe publishing failed: "
                            + e.getClass().getSimpleName()
            );
            serviceUnavailable(res);
            return;
        }

        writeMessage(
                res,
                201,
                "Swipe accepted for processing; eventId=" + eventId
        );
    }

    // Check path structure and the supported resort range.
    private boolean isUrlValid(String[] parts) {
        if (parts.length != 8) {
            return false;
        }

        if (!"seasons".equals(parts[2])
                || !"days".equals(parts[4])
                || !"skiers".equals(parts[6])) {
            return false;
        }

        try {
            int resortID = Integer.parseInt(parts[1]);
            int seasonID = Integer.parseInt(parts[3]);
            int dayID = Integer.parseInt(parts[5]);
            int skierID = Integer.parseInt(parts[7]);

            return resortID >= 1
                    && resortID <= 10
                    && seasonID > 0
                    && dayID > 0
                    && skierID > 0;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Preserve the original request-body limits.
    private boolean isLiftRideValid(LiftRide ride) {
        return ride.getTime() >= 1
                && ride.getTime() <= 360
                && ride.getLiftID() >= 1
                && ride.getLiftID() <= 40;
    }

    // Tell clients that this request could not be confirmed successfully.
    private void serviceUnavailable(HttpServletResponse res)
            throws IOException {

        res.setHeader("Retry-After", "1");

        writeMessage(
                res,
                503,
                "Publishing unavailable; retry with the same X-Event-ID"
        );
    }

    // Produce consistent JSON responses.
    private void writeMessage(
            HttpServletResponse res,
            int status,
            String message) throws IOException {

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setStatus(status);
        res.getWriter().write(gson.toJson(new ResponseMsg(message)));
    }
}
```

### 3. What each section does

**① Fields and `init()` obtain the existing shared components**

At initialization, the servlet gets the connection manager, publisher, and ingest instance's `serverId` from `ServletContext`. The lifecycle listener still owns resource creation and shutdown. If the components are unavailable, servlet initialization fails so problems such as a missing listener registration are visible.

**② `doGet()` retains the original informational endpoint**

It still checks the path and returns an informational message. Reading actual cumulative vertical feet is outside this endpoint's scope.

**③ The first part of `doPost()` validates the input**

In order, it checks:

1. Whether the path exists.
2. Whether the path structure and numbers are valid.
3. Whether the resort ID is between 1 and 10.
4. Whether `X-Event-ID` is a standard UUID.
5. Whether the request body parses as a `LiftRide`.
6. Whether `time` and `liftID` fall within the original ranges.

An invalid input returns `400` before publishing. The UUID check rejects shortened forms that the Java parser may accept and normalizes the stored ID to the standard lowercase form.

**④ Check readiness before publishing**

```java
if (!manager.isConnected())
```

Return `503` while the background task is initializing or recovering. A connection can still drop after this check, so the code also catches publishing exceptions.

**⑤ Construct the complete message**

The path supplies the resort, day, and skier. The body supplies the lift and business time. The server adds the official vertical-drop value, actual timestamp, and ingest instance ID.

Normalize numeric IDs, such as converting path value `01` to `"1"`, so one ID doesn't have multiple string representations. The code still validates `seasonID`, but the assignment's queue message format leaves it out of `SwipeMessage`.

**⑥ Return `201` after a successful publish**

```java
publisher.publish(message);
```

A normal return means the publish passed the confirmation and returned-message checks. The servlet then responds:

```text
201 Created
```

At this point, **the event has been accepted by the queue and awaits processing**. The leaderboard update happens later.

**⑦ Return `503` on publishing failure**

Network failures, confirmation timeouts, and unavailable resources return `503` with a message asking the client to retry with the original event ID. `Retry-After: 1` recommends waiting at least one second. The client is responsible for retrying.

If the thread is interrupted:

```java
Thread.currentThread().interrupt();
```

This restores the interrupt flag so upstream code can tell that the thread was asked to stop.

**The broker may have enqueued a message even if confirmation timed out.** Keep the same event ID when retrying so the consumer can deduplicate it. The current ingest service can enqueue the same ID more than once.

**⑧ Two helpers produce consistent responses**

- `serviceUnavailable()` builds the publishing-unavailable response.
- `writeMessage()` sets the HTTP status, JSON content type, and character encoding.

This keeps response formatting consistent across branches.

### 4. Verify by packaging

Save and run **Maven → ski-servlet → Lifecycle → package**. Expect:

```text
BUILD SUCCESS
```

You can skip the screenshot for this build. The runtime HTTP path still needs to be tested.

Next, configure the ingest server's Tomcat environment, deploy the WAR, and verify this flow with one HTTP request:

```text
HTTP → Servlet → RabbitMQ queue
```

Start the original ingest server again for that test and keep the RabbitMQ instance running.

> What does `SkierServlet` do, and why do we need to connect `SwipePublisher` to it?
> The build succeeded.

**`SkierServlet` handles incoming HTTP swipe requests. It uses `SwipePublisher` to send messages to RabbitMQ.**

For example, the client sends:

```text
POST /skiers/1/seasons/2026/days/1/skiers/1001
```

Request body:

```json
{"time":120,"liftID":1}
```

Tomcat uses the `/skiers/*` mapping in `web.xml` to pass the request to `SkierServlet.doPost()`.

| Component | Responsibility |
|---|---|
| Tomcat | Receives network requests and dispatches them to the servlet |
| `SkierServlet` | Validates the path, headers, and JSON, looks up the vertical drop, assembles the event, and chooses the HTTP status |
| `SwipePublisher` | Converts the event to JSON, borrows a channel, publishes the message, and checks the publisher confirmation |
| RabbitMQ | Stores and delivers messages |

**Why connect them?**

The earlier publishing check called `SwipePublisher` directly and verified the publisher on its own. Real clients send HTTP requests, so the servlet has to call the publisher to connect those two parts:

```text
Client HTTP request
    ↓
Tomcat
    ↓
SkierServlet: validate, look up vertical drop, and create SwipeMessage
    ↓
SwipePublisher: publish and confirm
    ↓
RabbitMQ
    ↓
Servlet returns 201
```

In Assignment 1, a valid request returned `201` immediately. Now the servlet returns `201` only after publishing succeeds, or `503` if publishing is unavailable. **The success response now means the event was handed off to the processing pipeline.**

Separate files keep HTTP handling and RabbitMQ communication distinct and make it easier to verify the publisher independently.

`BUILD SUCCESS` confirms the change builds. **Next, deploy the ingest service. Start the original `cs6650-lab1` instance in AWS, wait for the status checks to pass, and find its current Public IPv4 address.** Keep the RabbitMQ instance running.

Send the ingest instance's current public IP. I'll walk you through configuring the Tomcat environment variables, uploading the WAR, and testing a real HTTP swipe request.

> Your explanation is clear. Please show me what to do next.

Next, deploy the new backend to **the original ingest server**. We'll do this in three stages: start the instance and log in, configure Tomcat, then upload the WAR and test HTTP.

**This round covers startup and login. Leave the code as it is.**

### 1. Start the original ingest instance

Go to **AWS → EC2 → Instances** and find:

```text
cs6650-lab1-[zixin]
```

Its instance ID is:

```text
i-08ab6eec0d45941c6
```

Select it and click **Instance state → Start instance**.

Keep `cs6650-a2-rabbitmq` running too. This stage needs both machines:

| Instance | Purpose |
|---|---|
| `cs6650-lab1-[zixin]` | Tomcat and `server-v2` |
| `cs6650-a2-rabbitmq` | Message queues |

Wait until the original ingest instance shows **Running** and passes its status checks.

### 2. Find the ingest instance's current public IP

Select **the original ingest instance** and look under **Details → Public IPv4 address**.

The address may change after a stop and start, so use the current value from the console.

### 3. Log in to the ingest server from your Mac

Open a new Terminal window **on your Mac** and confirm that the prompt is:

```text
zixin@Mac ~ %
```

Run this command with the ingest server's current public IP in place of the placeholder:

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@CURRENT_INGEST_PUBLIC_IP
```

This connects to the instance running **Tomcat**. RabbitMQ runs on the separate instance.

### 4. Check the environment after logging in

In the remote terminal, run these commands in order, waiting for each to finish:

```bash
hostname -I
```

This displays the machine's IP addresses so you can confirm which instance you're on.

```bash
java -version
```

This checks that Java is available.

```bash
ls -ld /opt/tomcat9
```

This verifies that the Assignment 1 Tomcat directory is still there.

```bash
ls -l /opt/tomcat9/bin/setenv.sh
```

This checks whether Tomcat already has an environment configuration file. **If the last command says `No such file or directory`, that's okay for now.** We can create the file later. If it already exists, we'll inspect it and preserve the existing settings.

Send **the ingest server's current public IP and the output from these four commands**, and I'll give you the corresponding deployment commands. You don't need a report screenshot for this step.

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-7486658c-3d98-406f-a5f8-92a9157debc7.png`

The screenshot confirms that both instances have started:

| Instance | Public IP | Current status |
|---|---|---|
| Original ingest server | **`44.244.250.91`** | Running, status checks still initializing |
| RabbitMQ | Previously `54.69.65.232` | Running, 3/3 checks passed |

Once the ingest server passes its status checks, **open a new Terminal window on your Mac** and run:

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.244.250.91
```

After login, the prompt should look like:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

Look for **`34-32`** for the ingest server. The RabbitMQ server ends in `45-116`.

Run these commands one at a time:

```bash
hostname -I
```

```bash
java -version
```

```bash
ls -ld /opt/tomcat9
```

```bash
ls -l /opt/tomcat9/bin/setenv.sh
```

Send the four outputs. If the last command reports a missing file, that's expected for now. We'll create it later. Leave any existing configuration in place.

> Can I close the windows I opened earlier?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-3d7e77b8-9bc4-400a-9908-9ec989d03b23.png`

**You can close those old terminal windows. Your code, containers, and EC2 instances stay in place.** Closing a window ends any SSH session or tunnel running in it.

1. Press `Control + C` in the old tunnel window at the top, then close it. The `Connection refused` output may date from when RabbitMQ was stopped. That old output alone doesn't establish the current state.
2. Close any other terminal windows you no longer need.
3. Keep two management windows, one for the ingest server and one for RabbitMQ. You can close the tunnel for now because we aren't running the IntelliJ connection check.

**The command at the bottom of the screenshot includes `-N`.** That tells SSH to skip opening a remote shell, which is why nothing appears to happen.

Press `Control + C` in that window and run this complete command without `-N`:

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.244.250.91
```

Once you see:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

run these commands one at a time:

```bash
hostname -I
```

```bash
java -version
```

```bash
ls -ld /opt/tomcat9
```

```bash
ls -l /opt/tomcat9/bin/setenv.sh
```

Send the output so we can configure Tomcat.

> I already have a window with `[ec2-user@ip-172-31-34-32 ~]$`. Should I run the commands there?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-cb310e9d-152c-43a2-b225-6cc276a277c8.png`

If the prompt really is **`[ec2-user@ip-172-31-34-32 ~]$`**, you can run the commands there without reconnecting.

**The bottom-left prompt in this screenshot actually shows:**

```text
[ec2-user@ip-172-31-45-116 ~]$
```

That's the **RabbitMQ server**. Tomcat needs to be configured on the ingest server.

| Address in prompt | Server |
|---|---|
| `172-31-34-32` | Ingest server, where you configure Tomcat |
| `172-31-45-116` | RabbitMQ server, where you manage the container |

Open a new terminal on your Mac and run:

```bash
ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.244.250.91
```

Once the prompt shows **`34-32`**, run these commands one at a time:

```bash
hostname -I
```

```bash
java -version
```

```bash
ls -ld /opt/tomcat9
```

```bash
ls -l /opt/tomcat9/bin/setenv.sh
```

You can keep the RabbitMQ window open. Use the ingest server window to configure Tomcat.

> Why is nothing happening after I opened a new window?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-45935268-60c8-42ac-a28d-44d4f79b3442.png`

**This command omits `-N), so its syntax is correct.** SSH is still waiting to connect. You haven't logged in yet.

**The original ingest server's security group may not allow your current public IP to connect on port 22.** Earlier, we configured this permission only for the new RabbitMQ instance.

**1. Cancel the pending connection and check your public IP**

Press **`Control + C`** in the new terminal and run:

```bash
curl -4 https://checkip.amazonaws.com
```

Record the IPv4 address it prints.

**2. Check the ingest server's SSH rule**

In AWS:

1. Select **`cs6650-lab1-[zixin]`**.
2. Confirm that it is still `Running` and that its public IP is still `44.244.250.91`.
3. Click **Security** below.
4. Click the **`cs6650-lab1-sg`** security group.
5. Go to **Inbound rules → Edit inbound rules**.
6. Add this rule:

   | Field | Value |
   |---|---|
   | Type | SSH |
   | Port | Automatically set to `22` |
   | Source | Custom, with the IPv4 address you just looked up followed by `/32` |
   | Description | `SSH from my current IP` |

7. Click **Save rules**. Keep the existing rules, especially `8080`.

Edit **`cs6650-lab1-sg`** for this step. Use your Mac network's public IP as the source. The EC2 address is the destination.

**3. Reconnect with a 10-second connection timeout**

If the ingest instance's public IP is unchanged, run:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.244.250.91
```

After successfully logging in, you should see:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

If it still fails, send the specific error and a screenshot of the ingest security group's inbound rules. **We're still troubleshooting SSH, so leave the Java and Tomcat configuration alone for now.**

> It worked after I added the inbound rule. What's next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b68d08bc-70bb-4f09-8c6f-0e851fcbe79a.png`

**You're logged in to the ingest server:**

```text
[ec2-user@ip-172-31-34-32 ~]$
```

Check the existing Java and Tomcat setup so we can preserve its configuration. **Run the following commands one at a time in this window.**

**1. Confirm the machine's address**

```bash
hostname -I
```

The output should include `172.31.34.32`.

**2. Check Java**

```bash
java -version
```

Check which Java version is installed.

**3. Check the Tomcat directory**

```bash
ls -ld /opt/tomcat9
```

Confirm the directory exists and check who owns it.

**4. Check for an existing startup configuration file**

```bash
ls -l /opt/tomcat9/bin/setenv.sh
```

If you see:

```text
No such file or directory
```

it means `setenv.sh` hasn't been created yet. Tomcat can still be installed correctly.

Send all four outputs. Then we'll configure the RabbitMQ private address, account, and instance ID in **`setenv.sh`** before uploading the WAR. Leave the files alone for now. No report screenshot is needed for this step.

> What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-50ffcb0c-5b6a-4f56-af1b-8d6a595987ca.png`

<details><summary>2 previous messages</summary>

> The screenshot confirms that Java 11 and Tomcat are installed and that `setenv.sh` hasn't been created. Next, configure the environment variables Tomcat needs to connect to RabbitMQ. Store the password in a separate file with restricted permissions and keep it out of the Java source and this chat.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://raw.githubusercontent.com/apache/tomcat/9.0.x/bin/catalina.sh`
>
> </details>
</details>

The checks look good:

- You're on the ingest server at `172.31.34.32`.
- Java 11 is installed.
- `/opt/tomcat9` exists.
- You can create `setenv.sh` because it doesn't exist yet.

**Next, configure Tomcat's RabbitMQ environment variables. Run all commands below in the current ingest server terminal.**

### 1. Check whether the ingest server can reach RabbitMQ

Run:

```bash
timeout 5 bash -c 'exec 3<>/dev/tcp/172.31.45.116/5672'
```

Then run:

```bash
echo $?
```

- **`0`** means the TCP connection succeeded and you can continue.
- For another value or a connection error, stop here and send the result.

This uses RabbitMQ's **private IP and actual port, 5672**, without going through your Mac's SSH tunnel. It verifies network connectivity. Authentication comes later.

### 2. Create the password file

Create two configuration files on the server:

| File | Purpose |
|---|---|
| `/opt/tomcat9/conf/skitrak-rabbitmq.password` | Stores the RabbitMQ password separately |
| `/opt/tomcat9/bin/setenv.sh` | Loads environment variables when Tomcat starts |

First, create the password file with access restricted to `ec2-user` and administrators:

```bash
sudo install -o ec2-user -g ec2-user -m 600 /dev/null /opt/tomcat9/conf/skitrak-rabbitmq.password
```

- `-o ec2-user` sets the file owner.
- `-g ec2-user` sets the file group.
- `-m 600` allows only the owner to read and write.
- `/dev/null` provides an empty input file.

**Run this initialization command only once. Running it again empties the file.**

### 3. Enter and save the RabbitMQ password

Run:

```bash
read -r -s -p "RabbitMQ password: " rabbitmq_password
```

Enter the **latest password you set for the `skitrak` account** and press Return. The input is hidden. Use the existing password.

Then run:

```bash
printf '%s' "$rabbitmq_password" > /opt/tomcat9/conf/skitrak-rabbitmq.password
```

Finally, run:

```bash
unset rabbitmq_password
```

These commands hide the input and temporarily store it, write the password exactly as entered without adding quotes or a newline, and clear the temporary variable.

**Keep the password file out of Git and avoid displaying it with `cat`.**

### 4. Create `setenv.sh`

Create the file and set its permissions:

```bash
sudo install -o ec2-user -g ec2-user -m 600 /dev/null /opt/tomcat9/bin/setenv.sh
```

Then **copy the entire block below, including the first line and the closing `EOF`**:

```bash
cat > /opt/tomcat9/bin/setenv.sh <<'EOF'
#!/bin/sh

# Connect directly to RabbitMQ over the VPC network.
export RABBITMQ_HOST='172.31.45.116'
export RABBITMQ_PORT='5672'

# Load application credentials without embedding the password here.
export RABBITMQ_USERNAME='skitrak'
export RABBITMQ_PASSWORD="$(cat /opt/tomcat9/conf/skitrak-rabbitmq.password)"

# Use the virtual host and pool settings configured for this assignment.
export RABBITMQ_VHOST='/'
export RABBITMQ_CHANNEL_POOL_SIZE='16'

# Identify this ingest application instance in published events.
export SERVER_ID='ingest-1'
EOF
```

If Terminal shows `>`, it is still waiting for input. Enter the closing `EOF` on its own line and press Return to get back to the normal prompt.

- `cat > ... <<'EOF'` writes the enclosed text to the file.
- Quoting `'EOF'` prevents `$(cat ...)` from running while you're writing the file.
- `export` passes the variables to the Tomcat Java process.
- `$(cat ...)` reads the password file when Tomcat loads the configuration.
- `SERVER_ID` identifies this ingest instance in future swipe messages.

Tomcat's startup script reads `setenv.sh`, so you can leave `catalina.sh` alone. [Tomcat startup script](https://raw.githubusercontent.com/apache/tomcat/9.0.x/bin/catalina.sh)

### 5. Check the files

Check the shell syntax:

```bash
sh -n /opt/tomcat9/bin/setenv.sh
```

**No output usually means the syntax check passed.** This command doesn't start Tomcat or verify the password.

Then check the permissions:

```bash
ls -l /opt/tomcat9/bin/setenv.sh /opt/tomcat9/conf/skitrak-rabbitmq.password
```

Both files should show something like:

```text
-rw------- ... ec2-user ec2-user ...
```

The startup script sources `setenv.sh`, so the file doesn't need execute permission.

**Send the numeric result from step 1 and the final file permissions. Keep the password hidden.** No report screenshot is needed. Next, we'll upload the WAR, restart Tomcat, and verify a real HTTP request.

> Did this work? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-612d9f42-82fa-448b-8b5b-7a278c90fe2c.png`

<details><summary>2 previous messages</summary>

> The configuration files have been created, and their syntax and permissions look correct. The remaining check is whether Tomcat can use the settings to connect to RabbitMQ. Next, upload the WAR, restart Tomcat, and check the startup logs and HTTP endpoint. The new application will use its own path, `/ski-servlet-v2`, to distinguish it from Assignment 1.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://tomcat.apache.org/tomcat-9.0-doc/deployer-howto.html`
>
> </details>
</details>

**This step worked. `setenv.sh` passed the syntax check, and both files have permissions `600` and are owned by `ec2-user`.** We'll confirm the password when Tomcat connects to RabbitMQ.

Next, upload the WAR and start the new application. Pay attention to which commands run **on your Mac** and which run **on the ingest server**.

### 1. Repackage in IntelliJ

Run **Maven → ski-servlet → Lifecycle → package**.

Confirm `BUILD SUCCESS` so you can upload the latest build.

### 2. Upload the WAR from your Mac

**Open a new Terminal window on your Mac** and confirm the prompt:

```text
zixin@Mac ~ %
```

Run the entire command:

```bash
scp -o ConnectTimeout=10 -i /Users/zixin/.ssh/cs6650-lab1-key.pem /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war ec2-user@44.244.250.91:/tmp/ski-servlet-v2.war
```

It uploads the local WAR to `/tmp` on the ingest server under this name:

```text
ski-servlet-v2.war
```

If the local file is missing, check the actual WAR filename under `server-v2/target` in IntelliJ before trying again.

### 3. Confirm the upload on the ingest server

Return to the terminal with this prompt:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

Run:

```bash
ls -lh /tmp/ski-servlet-v2.war
```

It should show the file size and modification time.

### 4. Stop the old Tomcat process

On the **ingest server**, run:

```bash
/opt/tomcat9/bin/shutdown.sh
```

Wait a few seconds, then run:

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

- **No output** means no matching Tomcat process remains, so you can continue.
- **A Java or Tomcat process still appears:** stop here and send the output.
- If `shutdown.sh` reports a refused connection and the process check shows nothing, Tomcat was probably already stopped.

Restarting Tomcat ensures the new process reads the environment variables you just configured.

### 5. Install the new application

After confirming Tomcat is stopped, run:

```bash
sudo install -o ec2-user -g ec2-user -m 644 /tmp/ski-servlet-v2.war /opt/tomcat9/webapps/ski-servlet-v2.war
```

This copies the WAR into Tomcat's deployment directory, sets ownership to `ec2-user`, and applies normal read permissions for an application file.

The new deployment name gives the application this path:

```text
/ski-servlet-v2
```

Assignment 1 remains available at `/ski-servlet`. Tomcat supports deploying WAR files from `webapps`. [Official deployment guide](https://tomcat.apache.org/tomcat-9.0-doc/deployer-howto.html)

### 6. Start Tomcat and inspect the log

Run:

```bash
/opt/tomcat9/bin/startup.sh
```

Wait about 10 seconds, then run:

```bash
tail -n 100 /opt/tomcat9/logs/catalina.out
```

Look for:

```text
Messaging initialization scheduled for server ingest-1
```

and:

```text
RabbitMQ connection and channel pool are ready
```

The first message means initialization was scheduled. **The second confirms the messaging resources are ready.** Send the relevant log lines if you see `SEVERE`, authentication failures, or repeated reconnection warnings.

### 7. Check HTTP from the ingest server itself

Run:

```bash
curl -i --max-time 10 http://localhost:8080/ski-servlet-v2/health
```

The response should include:

```text
HTTP/1.1 200
```

and:

```json
{"status":"UP"}
```

**The current `/health` endpoint doesn't reflect RabbitMQ readiness.** Check the connection success log as well as the HTTP 200 response.

You can skip the report screenshot for now. Send **the relevant startup logs and health response**. Once confirmed, we'll send the first real HTTP swipe request and save the evidence as **`03-http-swipe-publish-pass.png`**.

> Did this work?
> What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-09564233-dd2e-4c65-b47a-380b1632a2e7.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-9066cc60-edc1-4c08-9a1d-2269d2637628.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-dd848db5-9161-4aae-97f0-217b0ad99066.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d7ba03d6-f290-4ec3-9941-0694604d83e2.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-2a00d4e9-9767-4f8a-ba9b-c74a58e8cf2e.png`

**Yes. The ingest service is deployed and has connected to RabbitMQ.** The screenshots show three pieces of evidence:

- Deployment of `ski-servlet-v2.war` completed.
- The log says `RabbitMQ connection and channel pool are ready`.
- `/ski-servlet-v2/health` returned `HTTP/1.1 200`.

The terminal showed `command not found` because you entered these log messages as commands:

```text
Messaging initialization scheduled ...
RabbitMQ connection and channel pool are ready
```

**Look for those lines in the log.** Entering them into the terminal didn't affect the deployment. The earlier connection refusal when stopping Tomcat is also consistent with Tomcat having already been stopped.

Next, verify that **an HTTP swipe request reaches the RabbitMQ queue**.

### 1. Record the initial queue counts in the RabbitMQ terminal

Find the RabbitMQ management terminal with **`172-31-45-116`** in its prompt and run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_queues -p / name messages_ready messages_unacknowledged consumers
```

| Column | Meaning |
|---|---|
| `messages_ready` | Messages waiting to be delivered to a consumer |
| `messages_unacknowledged` | Messages delivered but not yet acknowledged |
| `consumers` | Registered consumers |

Look at `resort.1`. The earlier publisher check removed its test message after passing, so the usual starting result is:

```text
resort.1    0    0    0
```

If the count is nonzero, record the actual count **without purging the queue**. Keep other publishing checks and clients stopped during this test.

### 2. Generate an event ID on the ingest server

Switch to the ingest server terminal with **`172-31-34-32`** in its prompt. Run:

```bash
swipe_event_id=$(cat /proc/sys/kernel/random/uuid)
```

Then run:

```bash
printf 'Event ID: %s\n' "$swipe_event_id"
```

The first command generates a UUID and stores it in a shell variable. The second prints it so you can compare it with the HTTP response.

**Send the request from the same terminal so it can read the variable.**

### 3. Send one real swipe request

Copy the entire line and **run it once**:

```bash
curl -i --max-time 20 -X POST 'http://localhost:8080/ski-servlet-v2/skiers/1/seasons/2026/days/1/skiers/1001' -H 'Content-Type: application/json' -H "X-Event-ID: $swipe_event_id" --data '{"time":120,"liftID":1}'
```

| Field | Value |
|---|---|
| Resort | 1 |
| Skier | 1001 |
| Lift | 1 |
| Business time | 120 |
| Vertical drop from the server lookup | 167 feet |

- `localhost:8080` reaches Tomcat directly on the ingest server without relying on the public rule for port 8080.
- `-X POST` sends the swipe request.
- `X-Event-ID` sends the stable event ID you just generated.
- `--data` supplies the request body.
- `-i` displays the response status and headers.
- `--max-time 20` limits the client's wait to 20 seconds.

The expected response includes:

```text
HTTP/1.1 201
X-Event-ID: the same ID generated above
```

The JSON response message should include:

```text
Swipe accepted for processing; eventId=...
```

**If you get a status other than `201`, send the output before attempting another request or generating another ID.**

### 4. Check the queue count on RabbitMQ again

Run the same command as in step 1:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_queues -p / name messages_ready messages_unacknowledged consumers
```

With no other programs publishing or consuming, the `messages_ready` count for `resort.1` should **increase by one**. If it started at zero, expect:

```text
resort.1    1    0    0
```

Leave this test message in the queue for now.

### 5. Save screenshots for the report

| Screenshot | Filename | Report location |
|---|---|---|
| Event ID, POST command, and `201` response | `03-http-swipe-publish-pass.png` | Test Results → HTTP-to-Queue Integration |
| Queue count change, preferably showing both the before and after outputs | `04-http-swipe-queue-count.png` | Immediately after the first screenshot, to show that the target queue gained one message |

Save both in:

```text
/Users/zixin/Downloads/6650/assignment2/results/
```

These screenshots show that the HTTP publishing path worked and that the queue count changed in a controlled test. The earlier `02-swipe-publisher-check-pass.png` supports the full check of the message fields.

Send the results after completing the one request and verifying the count.

> Why does the very first step say the command wasn't found?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-edf721b7-0919-4db8-b9bb-13cc5b084352.png`

**The command is correct. It ran on the wrong server.** The prompt in the screenshot is:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

That's the **Tomcat ingest server**. It doesn't have Docker installed, which explains `docker: command not found`. Docker belongs on the RabbitMQ server for this setup.

Run the first step on the **RabbitMQ server at `172.31.45.116`**.

If you no longer have a terminal open for RabbitMQ, open one **on your Mac** and run:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.69.65.232
```

This is RabbitMQ's last confirmed public IP. If the instance has been stopped and restarted since then, check its current address in AWS first.

After logging in, confirm the prompt is:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

Then run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_queues -p / name messages_ready messages_unacknowledged consumers
```

| Prompt suffix | Commands to run in that window |
|---|---|
| `34-32` | Tomcat, HTTP requests with `curl`, and generating event IDs |
| `45-116` | Docker, RabbitMQ, and inspecting queues |

Get the initial queue count first, then switch back to the `34-32` window to send the swipe request.

> Are these the right two screenshots? Is the first one 03 and the second one 04?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-a3e1dc97-49b4-4cee-a94e-a56e12f0be7b.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b99aef72-9ac1-465e-97ad-dba0892483f8.png`

**Yes. Save the first screenshot as 03 and the second as 04.**

| Screenshot | Filename | Evidence |
|---|---|---|
| First | `03-http-swipe-publish-pass.png` | HTTP returned `201`, and the response header contains the same event ID the request sent |
| Second | `04-http-swipe-queue-count.png` | The ready-message count for `resort.1` increased from **0 to 1**, while the other queues stayed at zero |

Save them in:

```text
/Users/zixin/Downloads/6650/assignment2/results/
```

Put both in **Test Results → HTTP-to-Queue Integration**. You can write:

> With no consumers or other publishers running, we sent one swipe request for resort 1. The ingest server returned HTTP 201, and the ready-message count in the target queue, `resort.1`, increased from 0 to 1.

Crop the previous `docker: command not found` error off the top of the first screenshot, keeping **the event ID, POST command, and complete response**. There's no need to resend the request.

The `Timeout: 60.0 seconds ...` line in the second screenshot is the command's timeout setting. The queue results below it show that the query succeeded.

> What percentage of the whole assignment have I completed? What should I do next?

**Judging by the remaining implementation, deployment, verification, and reporting, you've completed about 30%–35%, with 65%–70% left.** This estimates the work, independently of the grade.

You've verified the first live path:

```text
HTTP swipe request → Ingest server → RabbitMQ queue for the corresponding resort
```

You've also run checks for vertical-drop lookup, publisher confirms, message contents, and connection and channel pool recovery.

| Area | Current state |
|---|---|
| Producer, shared connection, channel pool, and topology | Implemented and verified with basic checks |
| HTTP ingest and RabbitMQ deployment | Verified the single-instance path |
| Consumer, deduplication, cumulative vertical feet, and top N | **Pending. This is the focus of the next stage** |
| Real leaderboard broadcasts and WebSocket updates | Pending. The leaderboard is still simulated |
| Client event ID changes and dynamic thread management | Pending |
| Consumer deployment, ALB, load tests with 2 and 4 instances, and monitoring | Pending |
| Report and AI usage reflection | Some screenshot evidence is ready. Final materials still need to be assembled |

Start by building **the smallest working consumer pipeline**:

```text
Read from the queue → Deduplicate by eventId → Add vertical feet
                   → Update top N → Manually acknowledge after success
```

First get the totals right and verify that duplicate messages aren't counted twice. Then add thread assignment across resorts, dynamic adjustment, and broadcasting.

**Prepare the next step in IntelliJ:**

1. Expand the root-level **`consumer`** folder.
2. Right-click **consumer → New → File**.
3. Create:

   ```text
   pom.xml
   ```

4. Leave the `server-v2` configuration where it is for now.

This file defines a **standalone Maven consumer project**. It uses Java 11, RabbitMQ, and Gson, and starts through `main()`. It runs independently of Tomcat and doesn't need a WAR.

Once you've created the file, we'll fill in the Maven configuration and go over the dependencies and how to run it. Then we'll create the message model and aggregator. Leave the test message in the queue.

> Is this the `consumer` folder at the project root?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-0e5fa753-f299-4bc4-9337-daad89a26a07.png`

**Yes, it's the `consumer` folder selected in your screenshot.** It's directly under `assignment2`, alongside `server-v2` and `client-part2`.

Right-click **`consumer` → New → File** and enter:

```text
pom.xml
```

The final path should be:

```text
assignment2/consumer/pom.xml
```

If Git prompts you, you can click **Add**. Create the empty file first. Next, we'll fill in the consumer project's Maven configuration.

> What do I do next?

<details><summary>2 previous messages</summary>

> Next, fill in the consumer's `pom.xml`, load it as the third independent Maven project, and create the source directories. This round only sets up the project and won't consume any existing queue messages.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html`
>
> </details>
</details>

Fill in **`consumer/pom.xml)** to make the consumer a standalone Java Maven project.

**The consumer starts through `main()` and is packaged as a JAR.** It doesn't need Tomcat, Servlet, or WebSocket dependencies.

### 1. Fill in `pom.xml`

Open the file you just created:

```text
assignment2/consumer/pom.xml
```

Paste the complete contents:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <!-- Identify the standalone consumer project. -->
    <groupId>cs6650.assignment2</groupId>
    <artifactId>consumer</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <!-- Compile for Java 11 and use UTF-8 source encoding. -->
    <properties>
        <maven.compiler.release>11</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Connect to RabbitMQ and consume or publish messages. -->
        <dependency>
            <groupId>com.rabbitmq</groupId>
            <artifactId>amqp-client</artifactId>
            <version>5.36.0</version>
        </dependency>

        <!-- Parse swipe events and serialize leaderboard updates. -->
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.10.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Use a fixed compiler plugin version. -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
        </plugins>
    </build>

</project>
```

### 2. What each part does

| Part | Purpose |
|---|---|
| XML declaration and `<project>` | Identifies the file as a Maven project configuration |
| `modelVersion` | Version of the POM model, independent of the installed Maven version |
| `groupId` | Organization identifier |
| `artifactId` | Project name, here `consumer` |
| `version` | Project version. `SNAPSHOT` indicates a development build |
| `packaging` | Builds a JAR. The server uses a WAR |
| `properties` | Sets Java 11 and UTF-8 |
| RabbitMQ dependency | Receives swipe messages and will later publish leaderboard updates |
| Gson dependency | Parses incoming JSON and generates leaderboard JSON |
| Compiler plugin | Pins a build tool version for more consistent builds |

The dependency versions match those used by the ingest server. The RabbitMQ Java client's 5.x series supports Java 11. [Official client documentation](https://www.rabbitmq.com/client-libraries/java-client)

**This configuration builds a standard JAR.** After creating the application entry point, we'll configure an executable JAR that includes runtime dependencies so you can launch it on EC2 with `java -jar`.

### 3. Load the Maven project

After saving:

1. Right-click **`consumer/pom.xml`** in the left pane.
2. Select **Add as Maven Project**.
3. Wait for synchronization.

The Maven window on the right should show three projects:

```text
client-part2
consumer
ski-servlet
```

If `consumer` appeared automatically, skip adding it again.

### 4. Create the source directories

Create this layout under `consumer`:

```text
consumer/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/
    │   └── resources/
    └── test/
        └── java/
```

1. Right-click **consumer → New → Directory**. Create `src`.
2. Under `src`, create `main` and `test`.
3. Under `main`, create `java` and `resources`.
4. Under `test`, create `java`.

If IntelliJ suggests the standard directories, you can select them directly.

- `main/java` holds the production consumer code.
- `main/resources` holds application resources and can be empty for now.
- `test/java` holds checks for aggregation, deduplication, and similar behavior.

This follows Maven's standard directory layout. [Official directory guide](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html)

### 5. Verify the project configuration

In the Maven window, run **consumer → Lifecycle → compile**. Expect:

```text
BUILD SUCCESS
```

**`No sources to compile`** is normal while there are no Java files. This checks that Maven recognizes the configuration and can resolve the dependencies.

**You can skip the report screenshot. No existing messages will be consumed.** Next, we'll define the consumer's swipe event model and implement cumulative vertical feet, top N rankings, and deduplication.

> Did I create these directories correctly?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d1a41bb6-be41-41db-962f-f975c57ea061.png`

**Most of it is correct. There's an extra `test` under `main`, and `resources` is missing.** The Maven window shows `consumer` as the third project, so it loaded correctly.

Your current layout is:

```text
consumer/
└── src/
    ├── main/
    │   ├── java/       ✓
    │   └── test/       ← 这里应为 resources
    └── test/
        └── java/       ✓
```

To fix it:

1. Right-click **`test` under `main`**.
2. Choose **Refactor → Rename** and rename it to:

   ```text
   resources
   ```

3. **Keep `test/java` under `src`, alongside `main`.**

The final layout should be:

```text
consumer/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/
    │   └── resources/
    └── test/
        └── java/
```

Click **Sync All Maven Projects** afterward. If the directories are empty, renaming won't affect code.

`BUILD SUCCESS` confirms that Maven built the project. Maven doesn't check for extra folders, which is why this location still needs correcting. No report screenshot is needed.

> Does this look right now? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-a06a2881-7afc-4563-87c9-a73dd86bfc2b.png`

**Yes. The directory structure is correct, and the Maven window shows the `consumer` project.**

Next, give the consumer **a data model for swipe messages**. Reuse the verified `SwipeMessage` class and its existing fields.

### 1. Create the `model` package in the consumer source tree

Right-click **consumer → src → main → java**, choose **New → Package**, and enter:

```text
model
```

Use the consumer's main Java directory. Both `server-v2` and `src/test/java` are separate source locations.

### 2. Copy the existing message model

Expand:

```text
server-v2 → src → main → java → model
```

Find **`SwipeMessage)** and:

1. Select it and press **`Command + C`**.
2. Select the newly created **`consumer/src/main/java/model`** package.
3. Press **`Command + V`**.
4. If a copy dialog opens, keep the name `SwipeMessage` and confirm that the destination is under **consumer**.
5. Click **Add** if Git prompts you.

**Copy the file so both projects retain their own copy.**

The final layout is:

```text
assignment2/
├── server-v2/
│   └── src/main/java/model/SwipeMessage.java
└── consumer/
    └── src/main/java/model/SwipeMessage.java
```

The first line of the consumer's file should still be:

```java
package model;
```

### 3. Why does the consumer need this class too?

The producer sends JSON bytes. The consumer reconstructs its own Java object from those bytes:

```text
Producer's SwipeMessage → JSON bytes → RabbitMQ → Consumer's SwipeMessage
```

The class gives the consumer access to:

- `eventId` for deduplication.
- `resortID` and `dayID` to identify the leaderboard.
- `skierID` to identify whose total to update.
- `vertical` to determine how much to add.
- `liftID`, `time`, `timestamp`, and `serverId` for validation, tracing, and later metrics.

The Maven projects build independently, so the package and class names can match. **The consumer can build without depending on the server WAR.**

Keep both message models identical for now and update both if the fields change. A larger project could extract a shared model module. This structure is simpler for the assignment.

### 4. What does the code do?

This is the same class we covered earlier:

| Code section | Purpose in the consumer |
|---|---|
| Nine `private final` fields | Store the incoming event data |
| Constructor | Creates a complete event for local checks or parsing logic |
| Getters | Let deduplication and aggregation components read the data |
| No setters | Keep event data from being changed during processing |

**The class represents data.** Connection management and accumulation happen elsewhere. Separate validation will check the parsed fields next, since successful deserialization alone doesn't establish that a message is valid.

### 5. Compile the consumer

Save and run **Maven → consumer → Lifecycle → compile**. Expect:

```text
BUILD SUCCESS
```

No report screenshot is needed, and this step won't consume the queued test message. Next, create **`SwipeMessageParser`** to parse and validate incoming JSON before aggregation.

> I followed the steps, but the copied consumer `SwipeMessage` file didn't have `package model;` on the first line, so I typed it myself. Is that okay? Compile reported BUILD SUCCESS, so why is `model` still red?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-dd862aaf-54fc-4e66-860f-9bd16d387670.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-2a9f8ff7-675c-463b-ac8e-c0dbc4412ec4.png`

**It's fine to add `package model;` manually. Move the file into the `model` package as well.** The package declaration currently doesn't match the file location.

The path at the bottom of the screenshot is:

```text
consumer/src/main/java/SwipeMessage.java
```

The correct path is:

```text
consumer/src/main/java/model/SwipeMessage.java
```

The left pane shows `model` and `SwipeMessage` at the same level, so the file wasn't pasted inside the package. IntelliJ may have removed the declaration based on the copy destination.

1. Select the **consumer's `SwipeMessage` file** in the left pane.
2. Press **`Command + X`**.
3. Select the **`model` package** above it.
4. Press **`Command + V`**.
5. If prompted to confirm the move or refactor, check that the destination is the consumer's `model` package, then confirm.

The final layout should be:

```text
consumer/
└── src/main/java/
    └── model/
        └── SwipeMessage
```

Keep the first line as:

```java
package model;
```

**Why does Maven succeed while IntelliJ still reports an error?** The Java compiler can compile some source files whose paths differ from their package declarations. IntelliJ checks the standard source directory structure and still reports:

```text
Package name 'model' does not correspond to the file path
```

After moving the file, save and run **consumer → Lifecycle → compile** again. Yellow `is never used` warnings are expected at this stage. **Keep the server's copy as it is and retain `package model;` in the consumer's file.**

> Can you estimate how many more hours it'll take to finish?
> Does this look right now?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-ecf6cb52-efd3-41f5-88a4-8753b527b0a9.png`

**The file is in the right place now.** `SwipeMessage` appears inside the consumer's `model` package, `package model;` has no red underline, and compilation succeeded. The remaining yellow warnings mean the code isn't used yet, which is expected.

Given your progress and our approach, where you make the changes and I explain and troubleshoot each part, **set aside 25–40 hours to finish all the remaining requirements**. Deployment or performance testing problems could push it beyond that range.

| Remaining work | Estimated time |
|---|---:|
| Consumer parsing, deduplication, cumulative vertical feet, top N, and correctness checks | 5–7 hours |
| Thread assignment, ordering within each resort, retries, and dynamic thread management | 4–6 hours |
| Broadcast backplane and real WebSocket leaderboards | 3–5 hours |
| Client event IDs, parameters, and validation changes | 2–3 hours |
| Consumer EC2, ingest instance replication, ALB, and health checks | 4–6 hours |
| Tests with 1, 2, and 4 instances, stress testing, monitoring, and tuning | 4–8 hours |
| Architecture documentation, results analysis, AI reflection, and PDF | 3–5 hours |
| **Total** | **25–40 hours** |

**Getting the basic path working, from a swipe through consumer aggregation to a real leaderboard update, will take about 8–12 more hours.** The full assignment includes the other items above.

My earlier estimate didn't fully account for the time you're spending getting familiar with IntelliJ, Terminal, and AWS. I've adjusted it based on your actual pace. You said the deadline was **November 8**, so time is tight. Prioritize the live processing path, followed by scaling the deployment and collecting test evidence.

We'll continue in this order: **`SwipeMessageParser` → aggregation and deduplication → queue consumption → leaderboard broadcasting**. I'll group the code and explanations by complete feature to spend less time stopping after individual directory or copy operations.

> Please keep walking me through the next step.

<details><summary>2 previous messages</summary>

> This step completes one small feature: build the consumer's JSON parser and validator, then run a local check. It won't connect to RabbitMQ or take messages off the queue. First, make sure invalid data can't reach the aggregation logic.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/JsonParser.html`
>
> </details>
</details>

Next, implement **consumer message parsing and validation**. Create two files, one production component and one local check:

```text
RabbitMQ 消息 JSON
    ↓
SwipeMessageParser：检查字段、类型和范围
    ↓
合法的 SwipeMessage
    ↓
后续聚合器
```

You can leave EC2 and the SSH tunnel stopped. This step won't consume any queued messages.

### 1. Create the production parser

Under the consumer's **`src/main/java`**, create this package:

```text
messaging
```

Create this class in the package:

```text
SwipeMessageParser
```

Its path should be:

```text
consumer/src/main/java/messaging/SwipeMessageParser.java
```

Paste:

```java
package messaging;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import model.SwipeMessage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public final class SwipeMessageParser {

    private SwipeMessageParser() {
    }

    // Parse and validate an event delivered by a specific resort queue.
    public static SwipeMessage parse(String json, int expectedResortID) {
        if (expectedResortID < 1 || expectedResortID > 10) {
            throw new IllegalArgumentException(
                    "Expected resort ID must be between 1 and 10"
            );
        }

        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }

        JsonElement parsed;

        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid JSON message", e);
        }

        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Message must be a JSON object"
            );
        }

        JsonObject root = parsed.getAsJsonObject();

        String suppliedId = requiredString(root, "eventId");
        String eventId = UUID.fromString(suppliedId).toString();

        if (!eventId.equalsIgnoreCase(suppliedId)) {
            throw new IllegalArgumentException(
                    "eventId must use the standard UUID format"
            );
        }

        String resortID = numericId(root, "resortID", 10);
        String dayID = numericId(root, "dayID", Integer.MAX_VALUE);
        String skierID = numericId(root, "skierID", Integer.MAX_VALUE);
        String liftID = numericId(root, "liftID", 40);

        if (Integer.parseInt(resortID) != expectedResortID) {
            throw new IllegalArgumentException(
                    "Message resortID does not match its queue"
            );
        }

        int vertical = requiredInt(root, "vertical", 60, 400);
        int time = requiredInt(root, "time", 1, 360);

        String timestamp = requiredString(root, "timestamp");

        try {
            Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "timestamp must be a valid UTC instant", e
            );
        }

        String serverId = requiredString(root, "serverId");

        return new SwipeMessage(
                eventId, resortID, dayID, skierID, liftID,
                vertical, time, timestamp, serverId
        );
    }

    // Reject missing, blank, or non-string fields.
    private static String requiredString(JsonObject root, String name) {
        JsonElement value = root.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {

            throw new IllegalArgumentException(
                    name + " must be a nonblank string"
            );
        }

        return value.getAsString();
    }

    // Validate numeric string IDs and normalize their representation.
    private static String numericId(
            JsonObject root, String name, int maximum) {

        String value = requiredString(root, name);
        int number = Integer.parseInt(value);

        if (number < 1 || number > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside the supported range"
            );
        }

        return Integer.toString(number);
    }

    // Require an exact integer rather than silently truncating decimals.
    private static int requiredInt(
            JsonObject root, String name, int minimum, int maximum) {

        JsonElement value = root.get(name);

        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {

            throw new IllegalArgumentException(
                    name + " must be a JSON number"
            );
        }

        int number;

        try {
            number = value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " must be an exact integer", e
            );
        }

        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside the supported range"
            );
        }

        return number;
    }
}
```

### 2. Understand the parser

**① `parse(json, expectedResortID)` is the consumer's entry point**

When consuming `resort.1`, call:

```java
SwipeMessageParser.parse(json, 1)
```

The second argument comes from **the resort associated with the queue**. If `resort.1` contains a message claiming to belong to resort 2, the parser rejects it so the event can't be counted for the wrong resort.

**② Parse JSON, then validate the fields**

Gson parses the text into a JSON structure. The parser validates each field and explicitly invokes the `SwipeMessage` constructor. Valid JSON syntax and a valid business event are two separate checks. [Gson parsing API](https://javadoc.io/static/com.google.code.gson/gson/2.10.1/com.google.gson/com/google/gson/JsonParser.html)

**③ `requiredString()` requires a nonblank string**

It rejects missing fields, `null`, arrays, objects, numbers and other non-string types, and strings that are empty or only whitespace.

For example, the assignment specifies a string for `liftID`, so use:

```json
"liftID": "1"
```

A JSON number is rejected.

**④ Normalize UUIDs and numeric IDs**

Convert the event ID to standard UUID text so capitalization doesn't affect deduplication. Normalize numeric IDs too, such as converting `"01"` to `"1"`, to keep state keys consistent.

**⑤ `requiredInt()` checks numeric fields**

`vertical` and `time` must be JSON numbers that can be converted exactly to integers:

- `167` is accepted.
- `"167"` is rejected because it's a string.
- `167.5` is rejected without truncation.
- Values outside the supported range are rejected.

The parser checks that vertical drop is between 60 and 400. The ingest server already looked up the value for the specific lift, so the consumer uses the message's `vertical` field for aggregation.

**⑥ Validate the timestamp**

`Instant.parse()` verifies that the timestamp represents a valid instant, matching the ingest server's `Instant.now().toString()` format.

**⑦ Report validation failures to the caller**

The parser throws an exception on invalid input. The consumer will handle retries, acknowledgments, and message removal. It needs to distinguish invalid data from temporary failures such as network problems. Invalid data should neither be retried indefinitely nor counted as a successful aggregation.

### 3. Create the local check

Under:

```text
consumer/src/test/java
```

create a **`messaging` package**, then create:

```text
SwipeMessageParserCheck
```

Paste:

```java
package messaging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import model.SwipeMessage;

public final class SwipeMessageParserCheck {

    public static void main(String[] args) {
        Gson gson = new Gson();

        // Build a known valid event without connecting to RabbitMQ.
        SwipeMessage original = new SwipeMessage(
                "6ca7b201-c51b-4c1b-8d6b-d9799b2a4001",
                "1",
                "1",
                "1001",
                "1",
                167,
                120,
                "2026-11-07T12:00:00Z",
                "parser-check"
        );

        JsonObject valid = gson.toJsonTree(original).getAsJsonObject();

        SwipeMessage result =
                SwipeMessageParser.parse(valid.toString(), 1);

        if (!gson.toJsonTree(result).equals(valid)) {
            throw new AssertionError(
                    "Parsed event does not match the original event"
            );
        }

        System.out.println("PASS: a valid event preserves all fields.");

        JsonObject missingId = valid.deepCopy();
        missingId.remove("eventId");
        expectRejected(missingId.toString(), 1, "missing eventId");

        JsonObject invalidUuid = valid.deepCopy();
        invalidUuid.addProperty("eventId", "not-a-uuid");
        expectRejected(invalidUuid.toString(), 1, "invalid UUID");

        expectRejected(valid.toString(), 2, "resort and queue mismatch");

        JsonObject decimalVertical = valid.deepCopy();
        decimalVertical.addProperty("vertical", 167.5);
        expectRejected(decimalVertical.toString(), 1, "decimal vertical");

        JsonObject stringVertical = valid.deepCopy();
        stringVertical.addProperty("vertical", "167");
        expectRejected(stringVertical.toString(), 1, "string vertical");

        JsonObject invalidLift = valid.deepCopy();
        invalidLift.addProperty("liftID", "41");
        expectRejected(invalidLift.toString(), 1, "invalid lift ID");

        JsonObject invalidTime = valid.deepCopy();
        invalidTime.addProperty("time", 361);
        expectRejected(invalidTime.toString(), 1, "invalid swipe time");

        JsonObject invalidTimestamp = valid.deepCopy();
        invalidTimestamp.addProperty("timestamp", "yesterday");
        expectRejected(invalidTimestamp.toString(), 1, "invalid timestamp");

        expectRejected("[]", 1, "non-object message");
        expectRejected("{", 1, "malformed JSON");

        System.out.println("All swipe message parser checks passed.");
    }

    // Fail if invalid input is accepted; let unexpected exceptions surface.
    private static void expectRejected(
            String json, int resortID, String label) {

        try {
            SwipeMessageParser.parse(json, resortID);
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS: rejected " + label + ".");
            return;
        }

        throw new AssertionError(
                "Expected rejection: " + label
        );
    }
}
```

The check has three parts:

- **Valid event:** Verify every field after parsing.
- **Invalid events:** Use `deepCopy()` to copy the original data and break one condition at a time so the checks don't affect each other.
- **`expectRejected()`:** Expect an `IllegalArgumentException`. Fail the check if the parser accepts the invalid message.

### 4. Compile and run

1. Save the files.
2. Run **Maven → consumer → Lifecycle → test-compile** to compile production and test sources.
3. Open **`SwipeMessageParserCheck.java`**.
4. Click the **green triangle next to `main()`**.
5. Select **Run 'SwipeMessageParserCheck.main()'**.

Use the green triangle next to this class. The toolbar may still be set to `RabbitMqRecoveryCheck`.

**No environment variables are needed.** A successful run prints several `PASS` lines and ends with:

```text
All swipe message parser checks passed.

Process finished with exit code 0
```

Save the successful screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/05-consumer-parser-check-pass.png
```

Place it under **Test Results → Consumer Input Validation**. It shows that invalid messages were rejected. Deduplication and aggregation correctness require separate checks.

Next, implement **cumulative vertical feet, top N, and deduplication**. Verify the results with known local events before connecting to the live queue.

> Does `test-compile` mean I should click `test` first and then `compile`?
> Is the output I got correct? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-2aca6e6d-d95e-4642-aa9e-affad47db574.png`

<details><summary>3 previous messages</summary>

> All the parser checks in the screenshot passed. `test-compile` is a distinct Maven phase. My earlier instruction that it would always appear in the Lifecycle list was inaccurate. Next, we'll implement the core aggregation logic: cumulative vertical feet, event deduplication, and the top N rankings, with each resort's state handled independently.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html`
>
> </details>
>
> Context compaction
>
> Source: automatic
> Status: completed
</details>

Yes. The screenshot shows that **all `SwipeMessageParserCheck` checks passed with exit code `0)**. This verifies message parsing and the invalid-input cases you tested.

`test-compile` is **the name of a single Maven phase**. You can invoke it directly:

| Phase | What it does |
|---|---|
| `compile` | Compiles `src/main/java` |
| `test-compile` | Compiles the main sources and `src/test/java` |
| `test` | Runs the preceding phases, then runs tests recognized by the test framework |

Your check has a `main()` method, so you can run it from the green triangle beside that method. Maven's `test` phase won't necessarily run standalone checks like this automatically. [Maven lifecycle documentation](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)

Save the screenshot as:

```text
results/05-consumer-parser-check-pass.png
```

Place it in the report section on **Consumer Message Parsing and Input Validation**.

Next, implement **`LeaderboardAggregator`**. It adds a swipe's vertical feet to the skier's total, filters duplicate events, and generates the leaderboard.

For example:

```text
Skier 1001: first swipe adds 167  → total is 167
Skier 1001: second swipe adds 74  → total is 241
The second message arrives again → total stays 241
```

Start with in-memory aggregation. We'll connect it to RabbitMQ consumption and leaderboard broadcasting later.

**1. Create the `aggregation` package**

Find this directory in IntelliJ's left pane:

```text
consumer → src → main → java
```

Right-click `java`, choose **New → Package**, and enter:

```text
aggregation
```

Right-click the new `aggregation` package, choose **New → Java Class**, and enter:

```text
LeaderboardAggregator
```

The full path should be:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/aggregation/LeaderboardAggregator.java
```

**2. Paste the complete code**

```java
package aggregation;

import model.SwipeMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LeaderboardAggregator {

    // Rank by total vertical descending, then skier ID ascending.
    private static final Comparator<Entry> RANKING =
            Comparator.comparingLong(Entry::getTotalVertical)
                    .reversed()
                    .thenComparingLong(
                            entry -> Long.parseLong(entry.getSkierID()));

    private final int topN;

    // Keep independent aggregation state for each resort and day.
    private final ConcurrentHashMap<String, State> states =
            new ConcurrentHashMap<>();

    // Detect duplicate event IDs and conflicting business data.
    private final ConcurrentHashMap<String, String> processedEvents =
            new ConcurrentHashMap<>();

    private final AtomicLong swipesProcessed = new AtomicLong();

    public LeaderboardAggregator(int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be positive");
        }
        this.topN = topN;
    }

    public Result aggregate(SwipeMessage message) {
        Objects.requireNonNull(message, "message must not be null");

        String key = message.getResortID() + ":" + message.getDayID();
        String fingerprint = fingerprint(message);
        State state = states.computeIfAbsent(key, ignored -> new State());

        // Serialize updates within one resort and day.
        synchronized (state) {
            String existing =
                    processedEvents.get(message.getEventId());

            if (existing != null) {
                verifyFingerprint(existing, fingerprint);
                return new Result(
                        false, false, state.version, snapshot(state));
            }

            long oldTotal =
                    state.totals.getOrDefault(message.getSkierID(), 0L);
            long newTotal =
                    Math.addExact(oldTotal, message.getVertical());

            // Claim the event ID before applying its business data.
            existing = processedEvents.putIfAbsent(
                    message.getEventId(), fingerprint);

            if (existing != null) {
                verifyFingerprint(existing, fingerprint);
                return new Result(
                        false, false, state.version, snapshot(state));
            }

            List<Entry> before = snapshot(state);

            // Replace the skier's previous ranking entry.
            if (oldTotal != 0L) {
                state.ranking.remove(
                        new Entry(message.getSkierID(), oldTotal));
            }

            state.totals.put(message.getSkierID(), newTotal);
            state.ranking.add(
                    new Entry(message.getSkierID(), newTotal));

            List<Entry> after = snapshot(state);
            boolean changed = !before.equals(after);

            if (changed) {
                state.version++;
            }

            swipesProcessed.incrementAndGet();

            return new Result(
                    true, changed, state.version, after);
        }
    }

    public long getTotalVertical(
            String resortID, String dayID, String skierID) {

        State state = states.get(resortID + ":" + dayID);

        if (state == null) {
            return 0L;
        }

        synchronized (state) {
            return state.totals.getOrDefault(skierID, 0L);
        }
    }

    public List<Entry> getLeaderboard(String resortID, String dayID) {
        State state = states.get(resortID + ":" + dayID);

        if (state == null) {
            return Collections.emptyList();
        }

        synchronized (state) {
            return snapshot(state);
        }
    }

    public long getSwipesProcessed() {
        return swipesProcessed.get();
    }

    private List<Entry> snapshot(State state) {
        List<Entry> entries = new ArrayList<>();

        for (Entry entry : state.ranking) {
            if (entries.size() == topN) {
                break;
            }
            entries.add(entry);
        }

        return Collections.unmodifiableList(entries);
    }

    private static String fingerprint(SwipeMessage message) {
        // Retry metadata may change, so exclude timestamp and server ID.
        return String.join(":",
                message.getResortID(),
                message.getDayID(),
                message.getSkierID(),
                message.getLiftID(),
                Integer.toString(message.getVertical()),
                Integer.toString(message.getTime()));
    }

    private static void verifyFingerprint(
            String existing, String incoming) {

        if (!existing.equals(incoming)) {
            throw new IllegalArgumentException(
                    "The event ID was reused with different business data");
        }
    }

    private static final class State {
        private final Map<String, Long> totals = new HashMap<>();
        private final TreeSet<Entry> ranking = new TreeSet<>(RANKING);
        private long version;
    }

    public static final class Entry {
        private final String skierID;
        private final long totalVertical;

        public Entry(String skierID, long totalVertical) {
            this.skierID = skierID;
            this.totalVertical = totalVertical;
        }

        public String getSkierID() {
            return skierID;
        }

        public long getTotalVertical() {
            return totalVertical;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) other;
            return totalVertical == entry.totalVertical
                    && skierID.equals(entry.skierID);
        }

        @Override
        public int hashCode() {
            return Objects.hash(skierID, totalVertical);
        }
    }

    public static final class Result {
        private final boolean applied;
        private final boolean leaderboardChanged;
        private final long version;
        private final List<Entry> leaderboard;

        private Result(
                boolean applied,
                boolean leaderboardChanged,
                long version,
                List<Entry> leaderboard) {

            this.applied = applied;
            this.leaderboardChanged = leaderboardChanged;
            this.version = version;
            this.leaderboard = leaderboard;
        }

        public boolean isApplied() {
            return applied;
        }

        public boolean isLeaderboardChanged() {
            return leaderboardChanged;
        }

        public long getVersion() {
            return version;
        }

        public List<Entry> getLeaderboard() {
            return leaderboard;
        }
    }
}
```

**3. Understand the parts of the file**

The main entry point is:

```java
public Result aggregate(SwipeMessage message)
```

The consumer will call this method after parsing a `SwipeMessage`.

| Code section | Purpose |
|---|---|
| `RANKING` | Sorts by descending cumulative vertical feet, breaking ties by ascending skier ID for stable results |
| `topN` | Sets the number of entries to return, as in `new LeaderboardAggregator(10)` |
| `states` | Stores state separately for each resort and day |
| `processedEvents` | Records processed event IDs so duplicates aren't counted twice |
| `swipesProcessed` | Counts unique events successfully applied |
| `State` | Holds totals, ordering, and leaderboard version for one resort on one day |
| `Entry` | Represents a skier and their cumulative vertical feet on the leaderboard |
| `Result` | Reports whether the event was new, whether the leaderboard changed, the current version, and the top N |

`aggregate()` works in this order:

```text
Find the state for the resort and day
        ↓
Check whether the event is a duplicate
        ↓
Calculate the new cumulative vertical feet
        ↓
Remove the skier's old ranking entry
        ↓
Store the new total and ranking entry
        ↓
Check whether the top N changed
        ↓
Return the processing result
```

The aggregator keeps totals and rankings for every skier and returns only the top N. A skier outside the leaderboard can still enter it by adding more vertical feet.

`fingerprint()` detects reuse of the same event ID with different business data. It excludes `timestamp` and `serverId` because a retry of the same swipe may reach a different ingest server at a different time.

`synchronized (state)` lets **only one thread at a time modify the state for a given resort and day**. Different resorts can be processed in parallel. Preserving RabbitMQ queue order also requires proper consumer assignment. Locking alone doesn't guarantee message order.

The state is currently in memory. **A consumer process restart won't automatically preserve totals or deduplication records.** This step also leaves leaderboard publishing and RabbitMQ acknowledgments for later.

**4. Compile the code**

Save the file and open the Maven panel on the right:

```text
consumer → Lifecycle → compile
```

Double-click `compile`. `BUILD SUCCESS` completes this step.

You can compile without EC2 or an SSH tunnel. Next, we'll run a local check for cumulative totals, duplicate events, leaderboard changes, and isolation across resorts and days.

> The build succeeded. What should I do next?

Next, **verify the aggregator's results**. `BUILD SUCCESS` confirms that the code compiles. Now check whether the totals, deduplication, rankings, and concurrent updates are correct.

Run this step locally in IntelliJ. **You can leave EC2 and the SSH tunnel stopped.**

**1. Create the check file**

Right-click **consumer → src → test → java**, choose **New → Package**, and enter:

```text
aggregation
```

Right-click the package, choose **New → Java Class**, and enter:

```text
LeaderboardAggregatorCheck
```

The full path should be:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/test/java/aggregation/LeaderboardAggregatorCheck.java
```

This file verifies the aggregator. The production consumer has a separate entry point.

**2. Paste the complete code**

```java
package aggregation;

import model.SwipeMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public final class LeaderboardAggregatorCheck {

    public static void main(String[] args) throws Exception {
        checkTotalsAndDuplicates();
        checkRanking();
        checkIsolation();
        checkConcurrentUpdates();

        System.out.println("All leaderboard aggregation checks passed.");
    }

    private static void checkTotalsAndDuplicates() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        SwipeMessage first = event("1", "1", "1001", 167);
        SwipeMessage second = event("1", "1", "1001", 74);

        LeaderboardAggregator.Result firstResult =
                aggregator.aggregate(first);
        aggregator.aggregate(second);

        require(firstResult.isApplied(),
                "The first event must be applied");

        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "The total must be 241");

        System.out.println("PASS: vertical totals accumulate correctly.");

        // A retry may arrive through another server at a later time.
        SwipeMessage retry = new SwipeMessage(
                second.getEventId(),
                second.getResortID(),
                second.getDayID(),
                second.getSkierID(),
                second.getLiftID(),
                second.getVertical(),
                second.getTime(),
                "2026-11-07T12:01:00Z",
                "another-ingest-server");

        LeaderboardAggregator.Result duplicate =
                aggregator.aggregate(retry);

        require(!duplicate.isApplied(),
                "A duplicate must not be applied");
        require(!duplicate.isLeaderboardChanged(),
                "A duplicate must not change the leaderboard");
        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "A duplicate must not increase the total");
        require(aggregator.getSwipesProcessed() == 2,
                "Only unique applied events must be counted");

        System.out.println("PASS: duplicate events are not counted twice.");

        SwipeMessage conflict = new SwipeMessage(
                second.getEventId(),
                "1",
                "1",
                "1001",
                "1",
                200,
                120,
                "2026-11-07T12:00:00Z",
                "aggregation-check");

        boolean rejected = false;

        try {
            aggregator.aggregate(conflict);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        require(rejected,
                "Conflicting data with the same event ID must be rejected");
        require(aggregator.getTotalVertical("1", "1", "1001") == 241,
                "Rejected data must not change the total");

        System.out.println("PASS: conflicting event ID reuse is rejected.");
    }

    private static void checkRanking() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(2);

        aggregator.aggregate(event("1", "1", "1001", 167));
        aggregator.aggregate(event("1", "1", "1002", 300));

        LeaderboardAggregator.Result outsideTop =
                aggregator.aggregate(event("1", "1", "1003", 100));

        require(outsideTop.isApplied(),
                "An event outside the top N must still be applied");
        require(!outsideTop.isLeaderboardChanged(),
                "An event below the top N must not change the leaderboard");

        List<LeaderboardAggregator.Entry> ranking =
                aggregator.getLeaderboard("1", "1");

        require(ranking.size() == 2,
                "The leaderboard must contain only the top two");
        expectEntry(ranking, 0, "1002", 300);
        expectEntry(ranking, 1, "1001", 167);

        System.out.println("PASS: only the top N skiers are returned.");

        LeaderboardAggregator.Result enteredTop =
                aggregator.aggregate(event("1", "1", "1003", 250));

        require(enteredTop.isLeaderboardChanged(),
                "Entering the top N must change the leaderboard");
        require(enteredTop.getVersion() == outsideTop.getVersion() + 1,
                "A changed leaderboard must advance its version");

        ranking = enteredTop.getLeaderboard();
        expectEntry(ranking, 0, "1003", 350);
        expectEntry(ranking, 1, "1002", 300);

        System.out.println("PASS: a previously unranked skier can enter the top N.");

        // Equal totals use numeric skier ID order.
        aggregator.aggregate(event("1", "1", "1001", 183));

        ranking = aggregator.getLeaderboard("1", "1");
        expectEntry(ranking, 0, "1001", 350);
        expectEntry(ranking, 1, "1003", 350);

        System.out.println("PASS: ties use a stable skier ID order.");
    }

    private static void checkIsolation() {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        aggregator.aggregate(event("1", "1", "1001", 167));
        aggregator.aggregate(event("2", "1", "1001", 300));
        aggregator.aggregate(event("1", "2", "1001", 74));

        require(aggregator.getTotalVertical("1", "1", "1001") == 167,
                "Resort 1 day 1 must remain independent");
        require(aggregator.getTotalVertical("2", "1", "1001") == 300,
                "Resort 2 must remain independent");
        require(aggregator.getTotalVertical("1", "2", "1001") == 74,
                "Day 2 must remain independent");
        require(aggregator.getLeaderboard("3", "1").isEmpty(),
                "An unused resort must have an empty leaderboard");

        System.out.println("PASS: resorts and days have independent state.");
    }

    private static void checkConcurrentUpdates() throws Exception {
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(3);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        // Submit the same event many times from competing threads.
        SwipeMessage shared = event("1", "1", "1001", 167);

        try {
            for (int i = 0; i < 100; i++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    aggregator.aggregate(shared);
                    return null;
                }));
            }

            start.countDown();

            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }

            require(aggregator.getTotalVertical("1", "1", "1001") == 167,
                    "Concurrent duplicates must be applied only once");
            require(aggregator.getSwipesProcessed() == 1,
                    "Concurrent duplicate count must be one");

            System.out.println("PASS: concurrent duplicates are applied once.");

            tasks.clear();

            // Different event IDs must all contribute to the same total.
            for (int i = 0; i < 100; i++) {
                SwipeMessage unique = event("1", "1", "1001", 74);

                tasks.add(executor.submit(() -> {
                    aggregator.aggregate(unique);
                    return null;
                }));
            }

            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }

            require(aggregator.getTotalVertical("1", "1", "1001") == 7567,
                    "Concurrent unique events must not lose updates");
            require(aggregator.getSwipesProcessed() == 101,
                    "All unique events must be counted");

            System.out.println("PASS: concurrent unique events lose no updates.");
        } finally {
            executor.shutdownNow();
        }
    }

    private static SwipeMessage event(
            String resortID,
            String dayID,
            String skierID,
            int vertical) {

        // These fixtures test aggregation without using the JSON parser.
        return new SwipeMessage(
                UUID.randomUUID().toString(),
                resortID,
                dayID,
                skierID,
                "1",
                vertical,
                120,
                "2026-11-07T12:00:00Z",
                "aggregation-check");
    }

    private static void expectEntry(
            List<LeaderboardAggregator.Entry> ranking,
            int index,
            String skierID,
            long total) {

        LeaderboardAggregator.Entry entry = ranking.get(index);

        require(skierID.equals(entry.getSkierID())
                        && total == entry.getTotalVertical(),
                "Unexpected ranking entry at index " + index);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

**3. What the checks cover**

| Method | What it checks |
|---|---|
| `checkTotalsAndDuplicates()` | `167 + 74 = 241`, duplicates do not increase the total, and reuse of the same event ID with different business data is rejected |
| `checkRanking()` | Returns only two entries, lets an unranked skier move into the top two, breaks ties consistently, and increments the version when the leaderboard changes |
| `checkIsolation()` | Calculates a skier's totals separately for each resort and day |
| `checkConcurrentUpdates()` | Counts a single event only once under concurrent duplicate submissions and preserves all increments from concurrent distinct events |
| `event()` | Builds test messages without entering all nine fields each time |
| `expectEntry()`, `require()` | Compare actual and expected results and throw if they differ |

The expected final vertical total in the concurrency test is:

```text
167 + 100 × 74 = 7567
```

The check constructs messages directly to test the aggregation algorithm. The test values aren't intended to validate the official lift-to-vertical mapping. RabbitMQ message ordering will be checked when the consumer is connected to the queue.

**4. Run the check**

Save the file, click the green triangle beside `main()`, and choose:

```text
Run 'LeaderboardAggregatorCheck.main()'
```

No environment variables are needed. If IntelliJ asks for a module, select **consumer**.

A successful run ends with:

```text
PASS: concurrent duplicates are applied once.
PASS: concurrent unique events lose no updates.
All leaderboard aggregation checks passed.

Process finished with exit code 0
```

Save the screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/06-consumer-aggregation-check-pass.png
```

Place it in the report section on **Consumer Aggregation Correctness and Concurrent Deduplication**. Once this check passes, we'll connect **RabbitMQ message retrieval → parsing → aggregation → manual acknowledgment**.

> What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4b9ca1a2-7ad2-4352-94d8-fd43b7c7bd30.png`

**All the aggregation checks in the screenshot passed, with exit code `0`.** The checks for cumulative totals, duplicate events, rankings, isolation, and concurrent updates all succeeded.

Save this screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/06-consumer-aggregation-check-pass.png
```

Place it in the report section on **Consumer Aggregation Correctness and Concurrent Deduplication**.

Next, connect the consumer to RabbitMQ. Start with **`ConsumerConfig.java`**, which lets you configure the RabbitMQ address, consumer thread count, prefetch count, and leaderboard size through environment variables.

**1. Create the file**

Right-click the consumer's **`src/main/java` → New → Package**, and enter:

```text
config
```

Right-click `config` → **New → Java Class**, and enter:

```text
ConsumerConfig
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/config/ConsumerConfig.java
```

**2. Paste the complete code**

```java
package config;

public final class ConsumerConfig {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final int consumerThreads;
    private final int prefetch;
    private final int topN;

    private ConsumerConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            int consumerThreads,
            int prefetch,
            int topN) {

        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.consumerThreads = consumerThreads;
        this.prefetch = prefetch;
        this.topN = topN;
    }

    public static ConsumerConfig fromEnvironment() {
        // Read connection settings and consumer tuning parameters.
        return new ConsumerConfig(
                requiredEnv("RABBITMQ_HOST"),
                intEnv("RABBITMQ_PORT", 5672, 1, 65535),
                requiredEnv("RABBITMQ_USERNAME"),
                requiredEnv("RABBITMQ_PASSWORD"),
                optionalEnv("RABBITMQ_VHOST", "/"),
                intEnv("CONSUMER_THREADS", 10, 1, 80),
                intEnv("CONSUMER_PREFETCH", 10, 1, 1000),
                intEnv("LEADERBOARD_TOP_N", 10, 1, 1000));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required environment variable: " + name);
        }

        // Preserve the exact value, including password characters.
        return value;
    }

    private static String optionalEnv(
            String name, String defaultValue) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static int intEnv(
            String name,
            int defaultValue,
            int minimum,
            int maximum) {

        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        final int parsed;

        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be an integer", exception);
        }

        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between "
                            + minimum + " and " + maximum);
        }

        return parsed;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public int getConsumerThreads() {
        return consumerThreads;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public int getTopN() {
        return topN;
    }
}
```

**3. What the code does**

This class **reads and validates configuration**. Creating it doesn't connect to RabbitMQ or take messages off a queue.

| Part | Purpose |
|---|---|
| First eight fields | Store RabbitMQ connection settings and consumer runtime settings |
| `private ConsumerConfig(...)` | Stores the parsed configuration in the object |
| `fromEnvironment()` | Builds the configuration from the current process's environment variables |
| `requiredEnv()` | Checks required values and reports missing settings clearly |
| `optionalEnv()` | Uses a default when a setting is absent |
| `intEnv()` | Checks that a value is an integer in the supported range |
| `get...()` methods | Let consumer components read the settings |

Three new settings:

| Environment variable | Default | Meaning |
|---|---:|---|
| `CONSUMER_THREADS` | 10 | Configured number of consumer worker threads |
| `CONSUMER_PREFETCH` | 10 | Maximum number of unacknowledged messages allowed on each consumer channel in the later setup |
| `LEADERBOARD_TOP_N` | 10 | Number of leaderboard entries to return per resort and day |

**Configured thread count and effective concurrency are separate.** The assignment has ten resorts, and messages for any single resort must be processed in order, so at most ten resort streams can aggregate in parallel. The setting accepts up to `80` for comparing configurations as required by the assignment. The worker assignment logic will still give each resort a single processor so several threads don't aggregate the same queue in parallel.

Dynamic scaling will require separate logic to monitor load, adjust the worker count, and transfer queue ownership.

**4. Compile**

Save and double-click:

```text
consumer → Lifecycle → compile
```

`BUILD SUCCESS` completes this step. **You can leave the program stopped and the environment variables unset. No screenshot is needed.**

Next, create a consumer worker that connects the parser and aggregator to RabbitMQ and manually acknowledges each message after successful processing.

> What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-98f84f09-d580-4c38-9d73-f43e94db3540.png`

<details><summary>2 previous messages</summary>

> The configuration class compiled successfully. Next, create a consumer worker to connect message delivery, parsing, aggregation, and manual acknowledgment, with a place to plug in leaderboard broadcasting later.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.rabbitmq.com/client-libraries/java-api-guide`
>
> </details>
</details>

Yes, `ConsumerConfig` compiled. Now create **`ConsumerWorker`**, which reads messages from the queues.

```text
RabbitMQ → parse the message → update cumulative vertical feet and leaderboard → output the result → acknowledge manually
```

In this step, create the worker. The next step will add the entry point that assigns all ten resorts and starts the workers.

**1. Create the file**

Under **consumer → src → main → java**, use this package:

```text
messaging
```

You already have this package. Right-click it → **New → Java Class**, and enter:

```text
ConsumerWorker
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/messaging/ConsumerWorker.java
```

**2. Paste the code**

```java
package messaging;

import aggregation.LeaderboardAggregator;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class ConsumerWorker implements Runnable {

    @FunctionalInterface
    public interface ResultSink {
        // Complete downstream handling before acknowledging the message.
        void accept(
                SwipeMessage message,
                LeaderboardAggregator.Result result) throws Exception;
    }

    private final Connection connection;
    private final List<Integer> resorts;
    private final int prefetch;
    private final LeaderboardAggregator aggregator;
    private final ResultSink sink;

    // RabbitMQ callbacks enqueue deliveries; the worker processes them.
    private final BlockingQueue<PendingDelivery> deliveries =
            new LinkedBlockingQueue<>();

    private volatile boolean stopRequested;
    private volatile boolean failed;
    private volatile boolean terminated;

    public ConsumerWorker(
            Connection connection,
            List<Integer> resorts,
            int prefetch,
            LeaderboardAggregator aggregator,
            ResultSink sink) {

        this.connection = Objects.requireNonNull(connection);
        this.aggregator = Objects.requireNonNull(aggregator);
        this.sink = Objects.requireNonNull(sink);

        if (resorts == null || resorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "A worker must own at least one resort");
        }

        if (prefetch <= 0) {
            throw new IllegalArgumentException(
                    "prefetch must be positive");
        }

        this.resorts = new ArrayList<>(resorts);
        this.prefetch = prefetch;

        for (int resort : this.resorts) {
            if (resort < 1 || resort > 10) {
                throw new IllegalArgumentException(
                        "Resort ID must be between 1 and 10");
            }
        }

        if (this.resorts.stream().distinct().count()
                != this.resorts.size()) {
            throw new IllegalArgumentException(
                    "A worker cannot own the same resort twice");
        }
    }

    @Override
    public void run() {
        Channel channel = null;

        try {
            // All channel operations belong to this worker thread.
            channel = connection.createChannel();

            if (channel == null) {
                throw new IOException("No channel is available");
            }

            channel.basicQos(prefetch);

            for (int resort : resorts) {
                subscribe(channel, resort);
            }

            System.out.println(
                    "Worker started for resorts " + resorts);

            while (!stopRequested) {
                if (failed || !channel.isOpen()) {
                    throw new IOException(
                            "The consumer channel is unavailable");
                }

                PendingDelivery pending =
                        deliveries.poll(500, TimeUnit.MILLISECONDS);

                if (pending != null) {
                    process(channel, pending);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            if (!stopRequested) {
                failed = true;
            }
        } catch (Exception exception) {
            failed = true;

            System.err.println(
                    "Worker failed for resorts " + resorts
                            + ": " + exception.getClass().getSimpleName());
        } finally {
            // Unacknowledged deliveries return to RabbitMQ on channel closure.
            if (channel != null) {
                try {
                    channel.abort();
                } catch (Exception ignored) {
                    // The channel may already be closed.
                }
            }

            terminated = true;

            System.out.println(
                    "Worker stopped for resorts " + resorts);
        }
    }

    private void subscribe(Channel channel, int resort)
            throws IOException {

        DefaultConsumer consumer = new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(
                    String consumerTag,
                    Envelope envelope,
                    AMQP.BasicProperties properties,
                    byte[] body) {

                deliveries.add(new PendingDelivery(
                        resort,
                        envelope.getDeliveryTag(),
                        body));
            }

            @Override
            public void handleCancel(String consumerTag) {
                failed = true;
            }
        };

        // Require exclusive consumer ownership of each resort queue.
        channel.basicConsume(
                "resort." + resort,
                false,
                "",
                false,
                true,
                null,
                consumer);
    }

    private void process(Channel channel, PendingDelivery pending)
            throws Exception {

        final SwipeMessage message;

        try {
            String json = new String(
                    pending.body, StandardCharsets.UTF_8);

            message = SwipeMessageParser.parse(
                    json, pending.resort);
        } catch (IllegalArgumentException exception) {
            // Invalid messages follow the queue's dead-letter configuration.
            channel.basicReject(pending.deliveryTag, false);

            System.err.println(
                    "Rejected invalid message from resort."
                            + pending.resort);
            return;
        }

        // Retry this delivery locally before processing later deliveries.
        for (int attempt = 1; ; attempt++) {
            try {
                LeaderboardAggregator.Result result =
                        aggregator.aggregate(message);

                // Duplicate deliveries also reach the sink for retry recovery.
                sink.accept(message, result);
                break;
            } catch (Exception exception) {
                if (attempt >= 3) {
                    throw exception;
                }

                Thread.sleep(100L * attempt);
            }
        }

        // Acknowledge only this delivery after successful processing.
        channel.basicAck(pending.deliveryTag, false);
    }

    public void requestStop() {
        stopRequested = true;
    }

    public boolean hasFailed() {
        return failed;
    }

    public boolean isTerminated() {
        return terminated;
    }

    private static final class PendingDelivery {
        private final int resort;
        private final long deliveryTag;
        private final byte[] body;

        private PendingDelivery(
                int resort, long deliveryTag, byte[] body) {

            this.resort = resort;
            this.deliveryTag = deliveryTag;
            this.body = body;
        }
    }
}
```

**3. What the code does**

**`ResultSink` provides an outlet for processing results**

```java
void accept(SwipeMessage message,
            LeaderboardAggregator.Result result) throws Exception;
```

After calculating the leaderboard, the worker passes the message and result to the sink. The next step will print processing results there. Later, the broadcast backplane will use it to publish real leaderboards. **The worker acknowledges the original swipe only if the sink succeeds.**

**The constructor defines the worker's responsibilities**

| Argument | Purpose |
|---|---|
| `connection` | Shared RabbitMQ connection created by the main application |
| `resorts` | List of resorts assigned to this worker |
| `prefetch` | Maximum number of unacknowledged messages each subscription can prefetch |
| `aggregator` | Aggregator shared across workers |
| `sink` | Logic that handles aggregation results |

For example, you can split ten resorts across two workers:

```text
Worker 1: 1, 3, 5, 7, 9
Worker 2: 2, 4, 6, 8, 10
```

**`deliveries` is a blocking queue between the delivery callback and the worker thread**

When RabbitMQ delivers a message, `handleDelivery()` places it in the local queue. The thread running `run()` parses, aggregates, and acknowledges deliveries one at a time, so multiple callback threads don't operate on this consumer channel concurrently.

The `LinkedBlockingQueue` has no explicit capacity. RabbitMQ's prefetch setting bounds the number of unacknowledged messages per subscription, limiting how many deliveries can accumulate locally.

**`subscribe()` subscribes to a resort queue**

```java
channel.basicConsume(
        "resort." + resort,
        false,
        "",
        false,
        true,
        null,
        consumer);
```

- The second argument, `false`, disables automatic acknowledgment.
- The fifth argument, `true`, requests exclusive consumption so another worker can't process the same resort concurrently.

The exclusivity applies to the **consumer subscription**. The previously declared durable queue remains non-exclusive. [Official Java client guide](https://www.rabbitmq.com/client-libraries/java-api-guide)

**`process()` handles one message**

```text
Parse the message
   ↓
Update cumulative vertical feet and the leaderboard
   ↓
Pass the result to the sink
   ↓
Acknowledge manually with basicAck
```

| Failure | Behavior |
|---|---|
| Invalid message format | Calls `basicReject(..., false)` to forward the message according to the existing dead-letter configuration |
| Temporary aggregation or result-handling failure | Retries locally up to three times before processing subsequent messages |
| All three attempts fail | Exits and closes the channel so unacknowledged messages are requeued |
| Acknowledgment fails | Exits. RabbitMQ may redeliver the message, and event deduplication prevents double counting |

The current dead-letter configuration sends rejected invalid messages to `skitrak.expired`. When you inspect that queue later, distinguish expired messages from invalid ones.

**Duplicate events still go to the sink.** The total may have been updated before a broadcast failed. A retry needs to complete the unfinished result handling even if the event has already been counted.

**4. Save and compile**

Double-click:

```text
consumer → Lifecycle → compile
```

`BUILD SUCCESS` completes the step. This class has no `main()`, so **there's nothing to run from the green triangle and no screenshot to save**.

This implements one consumer worker. Next, build the entry point that manages the shared connection, assigns resorts, and stops and recreates failed workers.

> The build succeeded. What should I do next?

Next, create **the `ConsumerMain` entry point** to wire together the files you've written and run the consumer.

```text
Read configuration
   ↓
Open a shared Connection
   ↓
Assign all ten resorts
   ↓
Start the ConsumerWorker instances
   ↓
On connection or worker failure, stop the old workers and reconnect
```

Running this program consumes actual queued messages. For now it prints aggregation results to the console. We'll add leaderboard broadcasting later.

**1. Create the file**

Under **consumer → src → main → java**, create the package:

```text
app
```

Right-click `app` → **New → Java Class**, and enter:

```text
ConsumerMain
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/app/ConsumerMain.java
```

Paste:

```java
package app;

import aggregation.LeaderboardAggregator;
import com.google.gson.Gson;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import config.ConsumerConfig;
import messaging.ConsumerWorker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConsumerMain {

    public static void main(String[] args) throws Exception {
        ConsumerConfig config = ConsumerConfig.fromEnvironment();

        // Preserve in-memory aggregation state across connection recovery.
        LeaderboardAggregator aggregator =
                new LeaderboardAggregator(config.getTopN());

        Gson gson = new Gson();
        AtomicBoolean stopping = new AtomicBoolean();
        Thread mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopping.set(true);
            mainThread.interrupt();
        }, "consumer-shutdown"));

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(10_000);
        factory.setRequestedHeartbeat(30);

        // Rebuild connections and subscriptions explicitly.
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);

        while (!stopping.get()) {
            Connection connection = null;
            List<ConsumerWorker> workers = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();

            try {
                connection = factory.newConnection("skitrak-consumer");

                int activeWorkers =
                        Math.min(config.getConsumerThreads(), 10);

                List<List<Integer>> assignments = new ArrayList<>();

                for (int i = 0; i < activeWorkers; i++) {
                    assignments.add(new ArrayList<>());
                }

                // Distribute resorts evenly without overlapping ownership.
                for (int resort = 1; resort <= 10; resort++) {
                    assignments.get((resort - 1) % activeWorkers)
                            .add(resort);
                }

                ConsumerWorker.ResultSink sink = (message, result) -> {
                    // This console sink will later publish to the backplane.
                    if (result.isLeaderboardChanged()
                            || !result.isApplied()) {

                        System.out.println(
                                "LEADERBOARD resort="
                                        + message.getResortID()
                                        + " day=" + message.getDayID()
                                        + " version=" + result.getVersion()
                                        + " entries="
                                        + gson.toJson(result.getLeaderboard()));
                    }
                };

                for (int i = 0; i < activeWorkers; i++) {
                    ConsumerWorker worker = new ConsumerWorker(
                            connection,
                            assignments.get(i),
                            config.getPrefetch(),
                            aggregator,
                            sink);

                    Thread thread = new Thread(
                            worker, "consumer-worker-" + (i + 1));

                    workers.add(worker);
                    threads.add(thread);
                    thread.start();
                }

                System.out.println(
                        "Consumer started: requestedThreads="
                                + config.getConsumerThreads()
                                + ", activeWorkers=" + activeWorkers
                                + ", prefetch=" + config.getPrefetch());

                while (!stopping.get()) {
                    if (!connection.isOpen()) {
                        throw new IllegalStateException(
                                "RabbitMQ connection was lost");
                    }

                    for (ConsumerWorker worker : workers) {
                        if (worker.hasFailed()
                                || worker.isTerminated()) {
                            throw new IllegalStateException(
                                    "A consumer worker stopped");
                        }
                    }

                    Thread.sleep(1000);
                }
            } catch (InterruptedException exception) {
                stopping.set(true);
            } catch (Exception exception) {
                System.err.println(
                        "Consumer recovery required: "
                                + exception.getClass().getSimpleName());
            } finally {
                for (ConsumerWorker worker : workers) {
                    worker.requestStop();
                }

                // Close the old connection before creating a replacement.
                if (connection != null) {
                    connection.abort();
                }

                // Wait until all old workers stop to prevent ownership overlap.
                joinWorkers(threads);
            }

            if (!stopping.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException exception) {
                    stopping.set(true);
                }
            }
        }

        System.out.println(
                "Consumer stopped. Unique swipes processed: "
                        + aggregator.getSwipesProcessed());
    }

    private static void joinWorkers(List<Thread> threads) {
        boolean interrupted = false;

        for (Thread thread : threads) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**2. What the code does**

| Part | Purpose |
|---|---|
| `ConsumerConfig.fromEnvironment()` | Reads the connection address, credentials, thread count, and other settings |
| `LeaderboardAggregator` | Provides one aggregator shared by all workers |
| `ConnectionFactory` | Sets RabbitMQ connection options and disables automatic recovery in the client library |
| `assignments` | Distributes the resorts evenly, assigning each resort once |
| `ResultSink` | Prints the real leaderboard for now. Later it will publish updates |
| `ConsumerWorker` and `Thread` creation | Starts the workers that consume messages |
| Inner monitoring loop | Checks whether the connection dropped or a worker failed |
| `finally` | Stops old workers, closes the old connection, and waits for the threads to exit |
| Outer loop | Waits five seconds before reconnecting and resubscribing |

The aggregator is created outside the reconnection loop. **A RabbitMQ reconnect preserves the running process's totals.** Stopping the entire Java application still loses in-memory data.

This version implements **a fixed worker count and failure recovery**. Dynamic scaling comes later. If you configure more than ten threads, it starts only ten active workers.

**3. Compile first**

In the Maven panel, double-click:

```text
consumer → Lifecycle → compile
```

Once it builds successfully, configure the run.

**4. Confirm the local RabbitMQ tunnel is open**

In a new **Terminal window on your Mac**, run the following. `54.69.65.232` was the RabbitMQ instance's public IP. If the instance has stopped and restarted, use the current address from the console:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@54.69.65.232
```

If the same tunnel is already running, reuse it.

**No output and no new prompt are normal. Keep the window open.** Run this on your Mac.

**5. Configure `ConsumerMain` in IntelliJ**

Click the green triangle beside `main()` once so IntelliJ creates a run configuration. A missing environment variable error on the first run is expected.

Then open:

```text
Run → Edit Configurations → ConsumerMain
```

Find **Environment variables**, open the table, and enter one row per variable:

| Name | Value |
|---|---|
| `RABBITMQ_HOST` | `127.0.0.1` |
| `RABBITMQ_PORT` | `5673` |
| `RABBITMQ_USERNAME` | `skitrak` |
| `RABBITMQ_PASSWORD` | The RabbitMQ password you set |
| `RABBITMQ_VHOST` | `/` |
| `CONSUMER_THREADS` | `10` |
| `CONSUMER_PREFETCH` | `10` |
| `LEADERBOARD_TOP_N` | `10` |

**Enter the values without wrapping quotes.** For `RABBITMQ_VHOST`, enter only `/`.

Confirm that the module is **consumer**, then click **Apply → Run**.

**6. Check the output**

A normal startup looks similar to:

```text
Consumer started: requestedThreads=10, activeWorkers=10, prefetch=10
Worker started for resorts [1]
Worker started for resorts [2]
...
Worker started for resorts [10]
```

The order can vary because threads start in parallel. If the previous test message is still in the queue and hasn't expired, you should also see something like:

```text
LEADERBOARD resort=1 day=1 version=1 entries=[{"skierID":"1001","totalVertical":167}]
```

**If the queue is empty, it's normal to see only the startup logs and for the process to keep running.** This is a long-running consumer. The earlier check programs were designed to exit on their own.

Compile and start the consumer, then leave it running. Next we'll send a new HTTP swipe request and verify that it travels through RabbitMQ and updates the real total here.

> Does this look right now? What do I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-5b4f18e2-82ba-4730-9434-0d8110b2e1e0.png`

Yes. **The consumer started and processed one RabbitMQ message:**

```text
resort=1 day=1
skierID=1001
totalVertical=167
```

Workers for all ten resorts started. Their order can vary. The red SLF4J output is a warning about a missing logging implementation. It didn't prevent this message from being consumed.

Next, verify that **a new swipe increases the total and that resending the same event leaves it unchanged**.

**1. Keep the consumer and SSH tunnel running**

Leave `ConsumerMain` and the tunnel window open. The current totals are held in memory.

**2. Send a new event from the ingest server**

Use the terminal with this prompt:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

This is the Tomcat ingest server. The RabbitMQ server's prompt ends in `45-116`.

Generate a new event ID:

```bash
swipe_event_id=$(cat /proc/sys/kernel/random/uuid)
```

Print it:

```bash
printf 'Event ID: %s\n' "$swipe_event_id"
```

Then run:

```bash
curl -i --max-time 20 -X POST \
  'http://localhost:8080/ski-servlet-v2/skiers/1/seasons/2026/days/1/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":121,"liftID":2}'
```

This adds a ride on **lift 2** for the same skier. Its official vertical drop is **74 feet**.

Expected HTTP response:

```text
HTTP/1.1 201
```

Back in IntelliJ, expect:

```text
LEADERBOARD resort=1 day=1 version=2 entries=[{"skierID":"1001","totalVertical":241}]
```

The previous 167 plus the new 74 equals 241.

**3. Resend the same event**

In **the same ingest server terminal**, run the same `curl` command again. **Reuse the UUID from the first request** so both requests carry the same event ID.

Expect:

- HTTP still returns `201`. The ingest server allows the event into the queue again.
- The consumer identifies the duplicate.
- The total stays at **241**.
- The leaderboard version stays at **2**.

The current code prints the leaderboard again for a duplicate, so you may see two identical lines with `241`. The total is only incremented once.

**4. Verify acknowledgment**

Switch to the **RabbitMQ server terminal**:

```text
[ec2-user@ip-172-31-45-116 ~]$
```

Run:

```bash
sudo docker exec skitrak-rabbitmq rabbitmqctl list_queues -p / name messages_ready messages_unacknowledged consumers
```

After processing finishes, expect these values for `resort.1`:

```text
messages_ready              0
messages_unacknowledged     0
consumers                  1
```

The queue has no messages waiting for delivery or acknowledgment. Together with the leaderboard output, this verifies that both requests were consumed and the duplicate was handled correctly.

Save two screenshots:

| Screenshot | Filename | Report section |
|---|---|---|
| IntelliJ output showing 167 initially, 241 after the new event, and 241 after the duplicate | `07-consumer-http-aggregation-dedup-pass.png` | End-to-End Aggregation and Event Deduplication |
| RabbitMQ queue counts and consumer counts | `08-consumer-queue-ack-pass.png` | Consumer Subscriptions and Manual Acknowledgment |

Save them in:

```text
/Users/zixin/Downloads/6650/assignment2/results/
```

Once this passes, we'll add the **RabbitMQ broadcast backplane** to send the real leaderboard to the WebSocket server.

> Are these screenshots 07 and 08, respectively? Does the output look correct?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-3652343f-84e9-432d-aa51-2e5f7c75352c.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bf82c93b-91a6-4d02-badc-614e8430f187.png`

Yes. **Save them as 07 and 08, respectively. Both outputs match expectations.**

| Screenshot | Filename | Report section |
|---|---|---|
| First | `07-consumer-http-aggregation-dedup-pass.png` | End-to-End Aggregation and Event Deduplication |
| Second | `08-consumer-queue-ack-pass.png` | Consumer Subscriptions and Manual Acknowledgment |

Save them in:

```text
/Users/zixin/Downloads/6650/assignment2/results/
```

The first screenshot shows:

```text
First swipe: 167, version=1
New swipe: 241, version=2
Duplicate: 241, version=2
```

The new **74 feet** were counted correctly, and the duplicate changed neither the total nor the leaderboard version. The last two lines match because the duplicate triggers another printout of the current leaderboard. That's expected.

In the second screenshot, all ten resort queues show:

- `messages_ready = 0`: no messages are waiting for delivery.
- `messages_unacknowledged = 0`: no messages are waiting for acknowledgment.
- `consumers = 1`: one consumer has subscribed per resort.

`skitrak.expired` is also empty. Together, the screenshots verify **HTTP → RabbitMQ → consumer → aggregation and deduplication → manual acknowledgment**.

The `Timeout: 60.0 seconds ...` line shows the command's configured timeout. The queue list below it confirms the command completed successfully.

Next, implement the **broadcast backplane** to send the real leaderboard from the consumer to the WebSocket server, which will push it to subscribed clients.

> Show me what to do next.

Next, implement **`LeaderboardPublisher`**, which sends real leaderboards to RabbitMQ's `leaderboard.fanout` exchange.

The full flow will be:

```text
Consumer calculates the leaderboard
        ↓
leaderboard.fanout
        ↓
One queue per WebSocket server
        ↓
Each server pushes the update to its own connected clients
```

Create the publisher in this step. Leave the running consumer unchanged for now.

**1. Create the file**

Right-click **consumer → src → main → java → messaging**, choose **New → Java Class**, and enter:

```text
LeaderboardPublisher
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/messaging/LeaderboardPublisher.java
```

**2. Paste the complete code**

```java
package messaging;

import aggregation.LeaderboardAggregator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class LeaderboardPublisher implements AutoCloseable {

    public static final String EXCHANGE = "leaderboard.fanout";

    private final Channel channel;
    private final String sourceId;
    private final Gson gson = new Gson();

    private boolean closed;

    public LeaderboardPublisher(
            Connection connection, String sourceId) throws IOException {

        Objects.requireNonNull(connection, "connection must not be null");

        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceId must not be blank");
        }

        this.sourceId = sourceId;

        Channel candidate = connection.createChannel();

        if (candidate == null) {
            throw new IOException("No publishing channel is available");
        }

        try {
            // Declare the backplane exchange and enable publisher confirms.
            candidate.exchangeDeclare(EXCHANGE, "fanout", true);
            candidate.confirmSelect();
        } catch (IOException | RuntimeException exception) {
            abortQuietly(candidate);
            throw exception;
        }

        this.channel = candidate;
    }

    public synchronized void publish(
            SwipeMessage message,
            LeaderboardAggregator.Result result)
            throws IOException, InterruptedException, TimeoutException {

        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(result, "result must not be null");

        if (closed || !channel.isOpen()) {
            throw new IOException(
                    "The leaderboard publishing channel is closed");
        }

        // Build a complete snapshot for one resort and day.
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "leaderboard");
        payload.addProperty("resortID", message.getResortID());
        payload.addProperty("dayID", message.getDayID());
        payload.addProperty("sourceId", sourceId);
        payload.addProperty("version", result.getVersion());
        payload.addProperty("timestamp", Instant.now().toString());
        payload.add(
                "entries", gson.toJsonTree(result.getLeaderboard()));

        byte[] body = gson.toJson(payload)
                .getBytes(StandardCharsets.UTF_8);

        AMQP.BasicProperties properties =
                new AMQP.BasicProperties.Builder()
                        .contentType("application/json")
                        .contentEncoding("UTF-8")
                        .deliveryMode(2)
                        .type("leaderboard")
                        .build();

        try {
            // Fanout exchanges route by bindings rather than routing keys.
            channel.basicPublish(
                    EXCHANGE,
                    "",
                    false,
                    properties,
                    body);

            channel.waitForConfirmsOrDie(5000);
        } catch (IOException
                 | InterruptedException
                 | TimeoutException
                 | RuntimeException exception) {

            // Discard a channel whose publishing outcome is uncertain.
            closed = true;
            abortQuietly(channel);
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        abortQuietly(channel);
    }

    private static void abortQuietly(Channel channel) {
        try {
            channel.abort();
        } catch (Exception ignored) {
            // The channel may already be closed.
        }
    }
}
```

**3. What each part does**

**`EXCHANGE` names the broadcast exchange**

```java
public static final String EXCHANGE = "leaderboard.fanout";
```

The WebSocket server will use the same name to receive the leaderboards published here.

A fanout exchange sends a copy of each message to every bound queue. Give **each WebSocket server its own queue** so each receives the update.

**The constructor prepares a reusable publishing channel**

```java
candidate.exchangeDeclare(EXCHANGE, "fanout", true);
candidate.confirmSelect();
```

The first line ensures the exchange exists. The `true` argument makes it durable. The second enables publisher confirms so the code can wait for RabbitMQ to confirm the publish.

The publisher reuses the `Connection` provided by the application and opens its own publishing `Channel`. It reuses both for subsequent messages.

When we integrate it, **each worker will have its own publisher** so ten workers don't contend for a single publishing channel.

**`publish()` builds and publishes a complete leaderboard**

A message looks like this:

```json
{
  "type": "leaderboard",
  "resortID": "1",
  "dayID": "1",
  "sourceId": "consumer-process-id",
  "version": 2,
  "timestamp": "2026-11-08T03:00:00Z",
  "entries": [
    {
      "skierID": "1001",
      "totalVertical": 241
    }
  ]
}
```

| Field | Purpose |
|---|---|
| `type` | Identifies a leaderboard update |
| `resortID`, `dayID` | Identify the resort and day |
| `sourceId` | Identifies the consumer process that produced the leaderboard |
| `version` | Identifies the version of this leaderboard within that process |
| `timestamp` | Records when the snapshot was published |
| `entries` | Contains the full set of top N entries |

Sending a **complete snapshot** lets the WebSocket server update its leaderboard directly.

The entry point will generate `sourceId` and keep it stable for the lifetime of the consumer process. Versions may start over when the process restarts, so comparing `version` alone across processes can give the wrong ordering.

**`synchronized` protects the publishing channel**

```java
public synchronized void publish(...)
```

It keeps multiple threads from publishing on one publisher's channel simultaneously and prevents `close()` from running during a publish.

**Publisher confirms and failure handling**

```java
channel.waitForConfirmsOrDie(5000);
```

Wait up to five seconds for RabbitMQ's confirmation. If the publish outcome is uncertain, close the channel and propagate the exception to the worker.

**A broker confirmation only confirms the publish at RabbitMQ.** These settings allow a publish even when there are no bound queues. We still need server subscription queues and a test of actual delivery to WebSocket clients.

`deliveryMode(2)` marks the message as persistent. Whether it survives a restart also depends on the receiving queue's type and durability settings.

**4. Save and compile**

In the Maven panel, double-click:

```text
consumer → Lifecycle → compile
```

`BUILD SUCCESS` completes this step. You don't need to run the class or save a screenshot. Next, update `ConsumerMain` to give each worker a publisher, then configure the WebSocket server to receive the broadcasts.

> The build succeeded. What should I do next?

Next, integrate `LeaderboardPublisher` into `ConsumerMain` so the consumer **publishes the leaderboard before acknowledging the original swipe message**.

Edit this one file:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/src/main/java/app/ConsumerMain.java
```

**1. Stop the running consumer**

Click the red Stop button in IntelliJ's `ConsumerMain` Run window. Totals and deduplication records are held in memory and will be lost on shutdown. Use fresh events to establish the expected totals in the next test.

You can leave the SSH tunnel open.

**2. Update the imports**

Remove:

```java
import com.google.gson.Gson;
```

Add:

```java
import messaging.LeaderboardPublisher;
import java.util.UUID;
```

Keep the other imports.

**3. Replace the `Gson` instance**

Find:

```java
Gson gson = new Gson();
```

Replace it with:

```java
// Keep one source ID throughout this consumer process.
String sourceId = UUID.randomUUID().toString();
```

This ID stays the same for the current Java process, including after reconnecting to RabbitMQ. Restarting the whole program generates a new ID.

**4. Remove the old console sink**

Delete this entire block:

```java
ConsumerWorker.ResultSink sink = (message, result) -> {
    // This console sink will later publish to the backplane.
    if (result.isLeaderboardChanged()
            || !result.isApplied()) {

        System.out.println(
                "LEADERBOARD resort="
                        + message.getResortID()
                        + " day=" + message.getDayID()
                        + " version=" + result.getVersion()
                        + " entries="
                        + gson.toJson(result.getLeaderboard()));
    }
};
```

We'll move it into the loop so **each worker has its own publisher and publishing channel**.

**5. Replace the worker creation loop**

Find this block:

```java
for (int i = 0; i < activeWorkers; i++) {
    ConsumerWorker worker = new ConsumerWorker(
            connection,
            assignments.get(i),
            config.getPrefetch(),
            aggregator,
            sink);

    Thread thread = new Thread(
            worker, "consumer-worker-" + (i + 1));

    workers.add(worker);
    threads.add(thread);
    thread.start();
}
```

Replace it with:

```java
for (int i = 0; i < activeWorkers; i++) {
    // Give each worker its own publishing channel.
    LeaderboardPublisher publisher =
            new LeaderboardPublisher(connection, sourceId);

    ConsumerWorker.ResultSink sink = (message, result) -> {
        // Republish snapshots for duplicate deliveries after a failed attempt.
        if (result.isLeaderboardChanged()
                || !result.isApplied()) {

            publisher.publish(message, result);

            System.out.println(
                    "BACKPLANE confirmed: resort="
                            + message.getResortID()
                            + " day=" + message.getDayID()
                            + " version=" + result.getVersion()
                            + " entries=" + result.getLeaderboard().size()
                            + " applied=" + result.isApplied());
        }
    };

    ConsumerWorker worker = new ConsumerWorker(
            connection,
            assignments.get(i),
            config.getPrefetch(),
            aggregator,
            sink);

    Thread thread = new Thread(
            worker, "consumer-worker-" + (i + 1));

    workers.add(worker);
    threads.add(thread);
    thread.start();
}
```

**6. Understand the new behavior**

A successful processing sequence now looks like this:

```text
Read the swipe message
      ↓
Parse, deduplicate, and update cumulative vertical feet
      ↓
If the leaderboard changed or this is a duplicate delivery:
publish the full leaderboard and wait for RabbitMQ confirmation
      ↓
Acknowledge the original swipe message
```

The following call invokes the leaderboard publisher:

```java
publisher.publish(message, result);
```

If it throws, `ConsumerWorker` uses its existing retry logic and skips the final `basicAck()`.

The condition:

```java
result.isLeaderboardChanged() || !result.isApplied()
```

asks for a publish in two cases:

| Case | Reason |
|---|---|
| Leaderboard changed | WebSocket servers need the updated leaderboard |
| Duplicate delivery | Aggregation may have succeeded before publishing failed, so publish the current snapshot again |

Workers have separate publishing channels but share one `Connection`. During recovery, the existing code closes the old connection, which also closes its consumer and publisher channels. The next loop iteration recreates these resources.

The log line:

```text
BACKPLANE confirmed
```

means that RabbitMQ confirmed the publish. WebSocket server and client delivery still need to be verified.

**7. Save and compile**

In Maven, double-click:

```text
consumer → Lifecycle → compile
```

`BUILD SUCCESS` completes this step. Skip the screenshot for now.

**Keep the consumer stopped and hold off on sending new test swipes.** Next, add the server-side subscription to give the broadcasts a destination, then start both sides and verify actual delivery.

> Is it the red Stop button in the upper-right corner, or somewhere else?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-23c505c9-a710-4c25-be98-77ad1072c3b7.png`

Yes. **It's the red square in the upper-right corner of your screenshot.** The tooltip says:

```text
Stop 'ConsumerMain' ⌘F2
```

Click it once to stop the consumer.

Then click the **`ConsumerMain`** tab at the bottom. When it shows `Process finished…` and the red Stop button dims or disappears, the consumer has stopped.

Follow the previous instructions to edit `ConsumerMain.java`, save it, and run **consumer → Lifecycle → compile** in Maven. Keep the SSH tunnel window open.

> The build succeeded. What should I do next?

<details><summary>2 previous messages</summary>

> Next, add server-side broadcast reception. I'll first review your WebSocket endpoint and startup listener so the new code keeps the existing subscription path and message format. You'll still edit the files manually in IntelliJ.
>
> <details><summary>Explored 2 files</summary>
>
> - Read `~/Downloads/6650/assignment2/server-v2/src/main/java/LeaderboardEndpoint.java`
> - Read `~/Downloads/6650/assignment2/server-v2/src/main/java/messaging/ApplicationLifecycleListener.java`
>
> </details>
</details>

Next, update **`server-v2`** so it can store the real leaderboard updates it receives from the broadcast backplane.

The existing `LeaderboardEndpoint` sends a fixed simulated leaderboard every 500 milliseconds. The client expects these fields:

```text
resortID
serverTimestamp
top → rank、skierID、vertical
```

We'll keep this client format and populate it with the actual totals.

First, create **`LeaderboardStore`** to convert and cache leaderboards. Keep the consumer stopped.

**1. Create the file in server-v2**

Use the **server-v2** source tree. Right-click:

```text
server-v2 → src → main → java
```

Choose **New → Package** and enter:

```text
leaderboard
```

Right-click that package, choose **New → Java Class**, and enter:

```text
LeaderboardStore
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/leaderboard/LeaderboardStore.java
```

**2. Paste the complete code**

```java
package leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ConcurrentHashMap;

public final class LeaderboardStore {

    // Cache snapshots independently for each resort and day.
    private static final ConcurrentHashMap<String, Snapshot> snapshots =
            new ConcurrentHashMap<>();

    private LeaderboardStore() {
    }

    public static Snapshot accept(String json) {
        JsonObject incoming =
                JsonParser.parseString(json).getAsJsonObject();

        String resortID = incoming.get("resortID").getAsString();
        String dayID = incoming.get("dayID").getAsString();
        String sourceId = incoming.get("sourceId").getAsString();
        long version = incoming.get("version").getAsLong();

        JsonObject outgoing = new JsonObject();
        outgoing.addProperty("resortID", resortID);
        outgoing.addProperty("dayID", dayID);
        outgoing.addProperty("sourceId", sourceId);
        outgoing.addProperty("version", version);
        outgoing.addProperty(
                "serverTimestamp",
                incoming.get("timestamp").getAsString());

        // Preserve the WebSocket message format used by Assignment 1.
        JsonArray top = new JsonArray();
        int rank = 1;

        for (JsonElement element
                : incoming.getAsJsonArray("entries")) {

            JsonObject entry = element.getAsJsonObject();
            JsonObject item = new JsonObject();

            item.addProperty("rank", rank++);
            item.addProperty(
                    "skierID",
                    Integer.parseInt(entry.get("skierID").getAsString()));
            item.addProperty(
                    "vertical",
                    entry.get("totalVertical").getAsLong());

            top.add(item);
        }

        outgoing.add("top", top);

        Snapshot candidate = new Snapshot(
                resortID,
                dayID,
                sourceId,
                version,
                outgoing.toString());

        String key = resortID + ":" + dayID;

        // Ignore duplicate or older versions from the same source.
        return snapshots.compute(key, (ignored, existing) -> {
            if (existing != null
                    && existing.sourceId.equals(sourceId)
                    && existing.version >= version) {
                return existing;
            }

            return candidate;
        });
    }

    public static Snapshot get(String resortID, String dayID) {
        return snapshots.get(resortID + ":" + dayID);
    }

    public static void clear() {
        snapshots.clear();
    }

    public static final class Snapshot {
        private final String resortID;
        private final String dayID;
        private final String sourceId;
        private final long version;
        private final String json;

        private Snapshot(
                String resortID,
                String dayID,
                String sourceId,
                long version,
                String json) {

            this.resortID = resortID;
            this.dayID = dayID;
            this.sourceId = sourceId;
            this.version = version;
            this.json = json;
        }

        public String getResortID() {
            return resortID;
        }

        public String getDayID() {
            return dayID;
        }

        public String getSourceId() {
            return sourceId;
        }

        public long getVersion() {
            return version;
        }

        public String getJson() {
            return json;
        }
    }
}
```

**3. What each part does**

**`snapshots` stores the latest leaderboard for each resort and day**

For example:

```text
"1:1" → Resort 1, day 1
"2:1" → Resort 2, day 1
"1:2" → Resort 1, day 2
```

The `ConcurrentHashMap` lets the broadcast receiver update the data while the WebSocket endpoint reads it.

**`accept()` receives, converts, and stores updates**

The consumer sends:

```json
{
  "entries": [
    {
      "skierID": "1001",
      "totalVertical": 241
    }
  ]
}
```

After conversion, the client reads:

```json
{
  "top": [
    {
      "rank": 1,
      "skierID": 1001,
      "vertical": 241
    }
  ]
}
```

Here, `vertical` is **the skier's cumulative vertical feet for that resort and day**.

`rank++` assigns ranks in the order already supplied by the consumer. The server keeps that ordering.

**`snapshots.compute()` stops duplicates or stale versions from overwriting newer data**

For the same consumer source:

```text
Stored version=5
Receive version=5 → keep the current snapshot
Receive version=4 → keep the current snapshot
Receive version=6 → update the snapshot
```

When `sourceId` changes, this implementation accepts the newly received source. That suits the current **single consumer application**. Multiple aggregation processes would require separate leader election or version coordination.

**`Snapshot` is an immutable leaderboard snapshot**

Each update creates a complete new object. The WebSocket endpoint calls:

```java
snapshot.getJson()
```

to obtain the converted message payload.

**`get()` and `clear()`**

- `get()` retrieves the snapshot for a particular resort and day.
- `clear()` clears the cache on application shutdown.

This is an in-memory server cache. RabbitMQ reception, WebSocket delivery, and strict validation of incoming messages are still pending.

**4. Compile**

Save and double-click:

```text
ski-servlet → Lifecycle → compile
```

The `ski-servlet` project in Maven corresponds to **server-v2**.

`BUILD SUCCESS` completes this step. You can skip running the class and taking a screenshot.

Next, create a broadcast subscriber. It will declare its own server queue, bind it to `leaderboard.fanout`, and call `LeaderboardStore.accept()` when messages arrive.

> The build succeeded. What should I do next?

<details><summary>2 previous messages</summary>

> Next, create the broadcast subscriber so each ingest server has its own queue for leaderboard updates. I'll check the connection manager interface first so the subscription channel can work with the existing recovery logic.
>
> <details><summary>Explored a file</summary>
>
> - Read `~/Downloads/6650/assignment2/server-v2/src/main/java/messaging/RabbitMqConnectionManager.java`
>
> </details>
</details>

Next, create the **`LeaderboardSubscriber` broadcast subscriber**.

```text
leaderboard.fanout
        ↓
This server's own queue
        ↓
LeaderboardSubscriber
        ↓
LeaderboardStore caches the real leaderboard
```

Edit one file and add another, then compile. We'll connect the subscriber to application startup and shutdown in the next step.

**1. Add a method to the connection manager**

In **server-v2**, open:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/messaging/RabbitMqConnectionManager.java
```

Add this import at the top:

```java
import com.rabbitmq.client.Channel;
```

After `getChannelPool()` and before `isConnected()`, add:

```java
// Create a long-lived subscription channel on the shared connection.
public Channel createSubscriptionChannel() throws IOException {
    Resources snapshot = current;

    if (closed || snapshot == null || !snapshot.pool.isHealthy()) {
        throw new IOException("RabbitMQ resources are not ready");
    }

    Channel channel = snapshot.connection.createChannel();

    if (channel == null) {
        throw new IOException("No subscription channel is available");
    }

    return channel;
}
```

This method **reuses the existing Connection** and creates a dedicated, long-lived subscription channel.

The subscription holds its channel open, so it needs a dedicated one. Holding a borrowed channel for the lifetime of the subscription would reduce the channels available for HTTP publishing.

**2. Create the subscriber**

In **server-v2 → src → main → java → messaging**, create:

```text
LeaderboardSubscriber
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/messaging/LeaderboardSubscriber.java
```

Paste:

```java
package messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import leaderboard.LeaderboardStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class LeaderboardSubscriber implements AutoCloseable {

    private static final Logger LOGGER =
            Logger.getLogger(LeaderboardSubscriber.class.getName());

    private static final String EXCHANGE = "leaderboard.fanout";

    private final RabbitMqConnectionManager manager;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private Channel channel;
    private boolean started;
    private boolean closed;
    private boolean subscriptionFailed;

    public LeaderboardSubscriber(RabbitMqConnectionManager manager) {
        this.manager = Objects.requireNonNull(manager);
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "The leaderboard subscriber is closed");
        }

        if (started) {
            return;
        }

        started = true;

        executor.scheduleWithFixedDelay(
                this::maintainSubscription,
                0,
                5,
                TimeUnit.SECONDS);
    }

    private synchronized void maintainSubscription() {
        if (closed) {
            return;
        }

        if (channel != null
                && channel.isOpen()
                && !subscriptionFailed) {
            return;
        }

        closeChannel();
        subscriptionFailed = false;

        try {
            Channel candidate = manager.createSubscriptionChannel();
            channel = candidate;

            candidate.exchangeDeclare(EXCHANGE, "fanout", true);

            // Create a separate temporary queue for this server connection.
            String queue = candidate.queueDeclare(
                    "",
                    false,
                    true,
                    true,
                    null).getQueue();

            candidate.queueBind(queue, EXCHANGE, "");
            candidate.basicQos(50);

            DefaultConsumer consumer = new DefaultConsumer(candidate) {
                @Override
                public void handleDelivery(
                        String consumerTag,
                        Envelope envelope,
                        AMQP.BasicProperties properties,
                        byte[] body) throws IOException {

                    receive(
                            candidate,
                            envelope.getDeliveryTag(),
                            body);
                }

                @Override
                public void handleCancel(String consumerTag) {
                    markSubscriptionFailed();
                }
            };

            candidate.basicConsume(queue, false, consumer);

            LOGGER.info(
                    "Leaderboard backplane subscribed: queue=" + queue);
        } catch (IOException | RuntimeException exception) {
            closeChannel();

            LOGGER.warning(
                    "Leaderboard subscription is not ready: "
                            + exception.getClass().getSimpleName()
                            + "; another attempt will follow");
        }
    }

    private synchronized void receive(
            Channel source,
            long deliveryTag,
            byte[] body) throws IOException {

        // Ignore callbacks from a replaced subscription.
        if (closed || source != channel || !source.isOpen()) {
            return;
        }

        final LeaderboardStore.Snapshot snapshot;

        try {
            String json = new String(body, StandardCharsets.UTF_8);
            snapshot = LeaderboardStore.accept(json);
        } catch (RuntimeException exception) {
            LOGGER.warning(
                    "Rejected malformed leaderboard update: "
                            + exception.getClass().getSimpleName());

            source.basicReject(deliveryTag, false);
            return;
        }

        source.basicAck(deliveryTag, false);

        LOGGER.info(
                "Leaderboard cached: resort=" + snapshot.getResortID()
                        + " day=" + snapshot.getDayID()
                        + " version=" + snapshot.getVersion());
    }

    private synchronized void markSubscriptionFailed() {
        subscriptionFailed = true;
    }

    public synchronized boolean isReady() {
        return !closed
                && channel != null
                && channel.isOpen()
                && !subscriptionFailed;
    }

    private void closeChannel() {
        Channel previous = channel;
        channel = null;

        if (previous != null) {
            try {
                previous.abort();
            } catch (Exception ignored) {
                // The old channel may already be closed.
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        executor.shutdownNow();
        closeChannel();
    }
}
```

**3. What the code does**

| Part | Purpose |
|---|---|
| Constructor | Uses the existing connection manager and its shared Connection |
| `start()` | Starts the background subscription maintenance task once |
| `maintainSubscription()` | Declares the exchange, separate queue, binding, and subscription |
| `receive()` | Receives JSON, updates the leaderboard cache, and acknowledges successful processing |
| `markSubscriptionFailed()` | Marks a RabbitMQ cancellation as a failure so the background task recreates the subscription |
| `isReady()` | Reports whether the subscription is currently available |
| `close()` | Stops the background task and closes the subscription channel on application shutdown |

Queue declaration:

```java
candidate.queueDeclare("", false, true, true, null)
```

| Argument | Meaning |
|---|---|
| `""` | RabbitMQ generates a unique queue name |
| `false` | The queue is non-durable |
| `true` | Only the current connection can use the queue |
| `true` | The queue is automatically deleted when its subscription ends |
| `null` | No additional queue arguments |

With two WebSocket servers, the layout is:

```text
leaderboard.fanout
        ├── Server A's queue
        └── Server B's queue
```

Both servers receive a copy of the update and push it to their own connected clients.

`synchronized` makes subscription maintenance, message reception, and shutdown mutually exclusive. The maintenance thread can't close the channel while a callback is using it.

**This is a live broadcast subscription.** The temporary queue is deleted when the connection drops. Resubscribing doesn't replay updates missed during the outage. A complete recovery design will need to deliver the latest snapshot to newly subscribed and reconnecting servers.

Invalid leaderboard messages are rejected. The current broadcast queue has no dead-letter configuration, so these invalid updates aren't retained.

**4. Compile**

Save both files and double-click:

```text
Maven → ski-servlet → Lifecycle → compile
```

`BUILD SUCCESS` completes the step. The startup listener doesn't invoke the subscriber yet, so you can skip redeployment, running it, and taking a screenshot.

Next, wire it into `ApplicationLifecycleListener`, then replace the simulated leaderboard in `LeaderboardEndpoint`.

> The build succeeded. What should I do next?

<details><summary>2 previous messages</summary>

> Next, wire the subscriber into server startup and have the WebSocket endpoint send real leaderboards from the cache. I'll first check the listener registration in `web.xml` so the subscriber starts exactly once.
>
> <details><summary>Explored a file</summary>
>
> - Read `~/Downloads/6650/assignment2/server-v2/web/WEB-INF/web.xml`
>
> </details>
</details>

Next, integrate the broadcast subscriber into the server and **replace the simulated WebSocket leaderboard with real data**.

Edit two files this time. Keep the consumer stopped until the server deployment is complete.

**1. Edit `ApplicationLifecycleListener.java`**

Open:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/messaging/ApplicationLifecycleListener.java
```

Find:

```java
private RabbitMqConnectionManager manager;
```

Add this below it:

```java
private LeaderboardSubscriber subscriber;
```

Then find:

```java
manager.start();
```

Add this below it:

```java
// Subscribe to leaderboard updates using the shared connection manager.
subscriber = new LeaderboardSubscriber(manager);
subscriber.start();
```

The subscriber waits for the connection in the background, so the startup thread can continue.

In `contextInitialized()`, find:

```java
} catch (RuntimeException e) {
    removeAttributes(context);
    manager.close();
    throw e;
}
```

Replace it with:

```java
} catch (RuntimeException e) {
    if (subscriber != null) {
        subscriber.close();
    }

    removeAttributes(context);
    manager.close();
    throw e;
}
```

Finally, in `contextDestroyed()`, find:

```java
if (manager != null) {
    manager.close();
}
```

Replace it with:

```java
// Stop the subscription before closing its shared connection.
if (subscriber != null) {
    subscriber.close();
}

if (manager != null) {
    manager.close();
}
```

These changes start and stop the subscriber with the application and clean up any resources that were created if an exception occurs. The listener is already registered in `web.xml`.

**2. Replace `LeaderboardEndpoint.java`**

Open:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/main/java/LeaderboardEndpoint.java
```

Replace the entire file with:

```java
import leaderboard.LeaderboardStore;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint implements ServletContextListener {

    private static final Map<Session, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    private static ScheduledExecutorService pusher;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        pusher = Executors.newSingleThreadScheduledExecutor();

        // Check for changed snapshots without generating synthetic data.
        pusher.scheduleWithFixedDelay(
                LeaderboardEndpoint::pushUpdates,
                50,
                50,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (pusher != null) {
            pusher.shutdownNow();
        }

        subscriptions.clear();
        LeaderboardStore.clear();
    }

    @OnOpen
    public void onOpen(
            Session session,
            @PathParam("resortID") String resortID) throws IOException {

        final String normalizedResort;
        final String dayID;

        try {
            int resort = Integer.parseInt(resortID);

            if (resort < 1 || resort > 10) {
                throw new IllegalArgumentException("Invalid resort ID");
            }

            normalizedResort = Integer.toString(resort);

            List<String> days =
                    session.getRequestParameterMap().get("dayID");

            int day = days == null || days.isEmpty()
                    ? 1
                    : Integer.parseInt(days.get(0));

            if (day <= 0) {
                throw new IllegalArgumentException("Invalid day ID");
            }

            dayID = Integer.toString(day);
        } catch (IllegalArgumentException exception) {
            session.close(new CloseReason(
                    CloseReason.CloseCodes.VIOLATED_POLICY,
                    "Invalid resortID or dayID"));
            return;
        }

        session.getAsyncRemote().setSendTimeout(5000);

        subscriptions.put(
                session,
                new Subscription(normalizedResort, dayID));
    }

    @OnClose
    public void onClose(Session session) {
        subscriptions.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            disconnect(session);
        }
    }

    private static void pushUpdates() {
        for (Map.Entry<Session, Subscription> entry
                : subscriptions.entrySet()) {

            Session session = entry.getKey();
            Subscription subscription = entry.getValue();

            if (!session.isOpen()) {
                subscriptions.remove(session);
                continue;
            }

            LeaderboardStore.Snapshot snapshot =
                    LeaderboardStore.get(
                            subscription.resortID,
                            subscription.dayID);

            // Send each cached snapshot at most once after successful delivery.
            if (snapshot == null
                    || snapshot == subscription.lastSent
                    || !subscription.sending.compareAndSet(false, true)) {
                continue;
            }

            try {
                session.getAsyncRemote().sendText(
                        snapshot.getJson(),
                        result -> {
                            if (result.isOK()) {
                                subscription.lastSent = snapshot;
                            } else {
                                disconnect(session);
                            }

                            subscription.sending.set(false);
                        });
            } catch (RuntimeException exception) {
                subscription.sending.set(false);
                disconnect(session);
            }
        }
    }

    private static void disconnect(Session session) {
        subscriptions.remove(session);

        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException ignored) {
            // The connection may already be unavailable.
        }
    }

    private static final class Subscription {
        private final String resortID;
        private final String dayID;
        private final AtomicBoolean sending = new AtomicBoolean();

        private volatile LeaderboardStore.Snapshot lastSent;

        private Subscription(String resortID, String dayID) {
            this.resortID = resortID;
            this.dayID = dayID;
        }
    }
}
```

This file is in the default package, so leave out a `package` declaration.

**3. What the new endpoint does**

| Part | Purpose |
|---|---|
| `subscriptions` | Tracks local WebSocket connections and the resort and day each connection subscribes to |
| `contextInitialized()` | Starts a task that checks the cache for updates every 50 milliseconds |
| `onOpen()` | Validates the resort and day and registers the connection |
| `pushUpdates()` | Sends real leaderboards asynchronously when a new snapshot is available |
| `sending` | Prevents two simultaneous sends on one connection |
| `lastSent` | Remembers the last successfully sent snapshot to avoid repeated sends |
| `onClose()`, `onError()` | Remove disconnected sessions |
| `contextDestroyed()` | Stops the scheduled task and clears the application's cache |

The scheduled task now **checks the real cache for changes**. The code that generated simulated leaderboards has been removed.

The endpoint sends the latest snapshot. If updates arrive faster than a client can receive them, intermediate versions may be skipped in favor of the latest version. A WebSocket message for every swipe isn't guaranteed.

**The subscription path still works with the original client:**

```text
/leaderboard/1
```

The default is day `1` when no day is specified. You can also specify:

```text
/leaderboard/1?dayID=2
```

This subscribes to resort `1`, day `2`.

When a client connects, the next check sends the real leaderboard if a snapshot is already cached for that resort and day. Otherwise, it waits for an update.

This change delivers snapshots currently in the cache. Catching up on broadcasts missed during a server disconnect remains pending.

**4. Compile and package**

Save both files and double-click this in Maven:

```text
ski-servlet → Lifecycle → compile
```

After it succeeds, double-click:

```text
ski-servlet → Lifecycle → package
```

`package` creates a WAR containing these changes:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war
```

You can skip the screenshot for this step. Next, upload the WAR to the ingest server, restart Tomcat, and verify the subscription and real WebSocket updates.

> I've finished. What should I do next?

Deploy the new WAR to the ingest server and verify that the broadcast subscription works. **Keep `ConsumerMain` stopped for now.**

Use these two terminals:

| Terminal prompt | Purpose |
|---|---|
| `zixin@Mac … %` | Upload the WAR |
| `[ec2-user@ip-172-31-34-32 …]$` | Deploy the WAR and start Tomcat |

**1. Upload the WAR from your Mac**

The command uses the ingest server's previous public IP, `44.244.250.91`. If the instance has restarted, check its current IP in the AWS console and replace the address in the command.

```bash
scp -o ConnectTimeout=10 \
  -i /Users/zixin/.ssh/cs6650-lab1-key.pem \
  /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war \
  ec2-user@44.244.250.91:/tmp/ski-servlet-v2.war
```

When the transfer reaches `100%`, the upload is complete.

**2. Stop Tomcat on the ingest server**

Use the existing `ip-172-31-34-32` window. If you aren't connected, run this in a new Mac terminal:

```bash
ssh -o ConnectTimeout=10 \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -i /Users/zixin/.ssh/cs6650-lab1-key.pem \
  ec2-user@44.244.250.91
```

Confirm the prompt shows `ip-172-31-34-32`, then run:

```bash
/opt/tomcat9/bin/shutdown.sh
```

Check the process:

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

**Continue only when there's no output.** If the process is still listed, wait a few seconds and check again.

If `shutdown.sh` reports a refused connection on `8005` and the process check shows nothing, Tomcat was probably already stopped.

**3. Replace the WAR and start Tomcat**

Still on the ingest server, run:

```bash
sudo install -o ec2-user -g ec2-user -m 644 \
  /tmp/ski-servlet-v2.war \
  /opt/tomcat9/webapps/ski-servlet-v2.war
```

This replaces the previously deployed Assignment 2 WAR. The context path remains `/ski-servlet-v2`.

Start Tomcat:

```bash
/opt/tomcat9/bin/startup.sh
```

**4. Check the startup logs**

Run:

```bash
tail -n 100 /opt/tomcat9/logs/catalina.out
```

After about ten seconds, look for the following messages **after the timestamp of this startup**:

```text
RabbitMQ connection and channel pool are ready
```

and:

```text
Leaderboard backplane subscribed: queue=amq.gen-...
```

The second line shows that the server created its own broadcast queue and subscribed to `leaderboard.fanout`.

You may briefly see this during startup:

```text
Leaderboard subscription is not ready
```

The connection may still be initializing. A subsequent `Leaderboard backplane subscribed` line confirms a successful retry.

**Read these messages in the log. They're output rather than commands.**

**5. Check HTTP**

Run:

```bash
curl -i --max-time 10 \
  http://localhost:8080/ski-servlet-v2/health
```

Expected response:

```text
HTTP/1.1 200
```

Check the subscription log too. The current `/health` endpoint alone doesn't verify the broadcast subscription.

Save a screenshot showing **the connection and subscription success messages from this startup**:

```text
/Users/zixin/Downloads/6650/assignment2/results/09-server-backplane-subscription-ready.png
```

Put it in the report section **Broadcast Backplane and Server Subscription**.

Leave Tomcat running. Next, connect a WebSocket client, start the consumer, and send a swipe to verify that the client receives a real leaderboard.

> I've completed step 1. What does step 2 mean, and what should I do?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-03086c56-438f-4a97-a421-b3642bb5cdd0.png`

**You're already logged in to the ingest server. You can skip the SSH command.**

The final prompt in the screenshot is:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

That identifies the current window as the ingest server we need.

The earlier error came from running the login command inside EC2. The private key path `/Users/zixin/.ssh/...` exists on your Mac. EC2 doesn't have that file. The error didn't affect the WAR you already uploaded.

Run these steps in **the current window**:

1. Stop Tomcat:

```bash
/opt/tomcat9/bin/shutdown.sh
```

2. Check whether it stopped:

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

**Continue when there's no output.** If a process still appears, wait a few seconds and check again.

3. Replace the WAR:

```bash
sudo install -o ec2-user -g ec2-user -m 644 /tmp/ski-servlet-v2.war /opt/tomcat9/webapps/ski-servlet-v2.war
```

4. Start Tomcat:

```bash
/opt/tomcat9/bin/startup.sh
```

5. Wait about ten seconds, then check the log:

```bash
tail -n 100 /opt/tomcat9/logs/catalina.out
```

Look for these messages from the current startup:

```text
RabbitMQ connection and channel pool are ready
Leaderboard backplane subscribed: queue=amq.gen-...
```

Send the log output so we can verify deployment and subscription.

> Is this right? What's next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-fbe6e589-cf33-4c43-b573-b499b907b564.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-b0f36f6e-3c5a-432a-ad7a-8a6503e6d26b.png`

The steps are in the right order. **The screenshots don't yet show that the new application has finished starting.**

The second screenshot shows that startup began at `04:09:36` and Tomcat started deploying the new `ski-servlet-v2.war`. The screenshot ends before a successful broadcast subscription appears. The connection success at `01:43` is from an earlier run.

Continue in **the current ingest server window**. You can leave Tomcat running.

**1. Wait ten seconds and check the latest log**

```bash
tail -n 80 /opt/tomcat9/logs/catalina.out
```

Look for these messages after `04:09:36`:

```text
RabbitMQ connection and channel pool are ready
Leaderboard backplane subscribed: queue=amq.gen-...
```

and:

```text
Server startup in [...]
```

The message:

```text
An expanded directory ... will be deleted
```

means Tomcat is replacing the old unpacked WAR directory. That's normal during deployment.

**2. Check HTTP**

```bash
curl -i --max-time 10 http://localhost:8080/ski-servlet-v2/health
```

Expected response:

```text
HTTP/1.1 200
```

If both the connection and subscription success messages appear for this startup, save the screenshot as:

```text
results/09-server-backplane-subscription-ready.png
```

Put it in **Broadcast Backplane and Server Subscription** in the report.

If the success messages are absent or you see `SEVERE` or repeated retries, send the latest log and `curl` output. Keep the consumer stopped for now.

> Is it there? Does it appear after `04:09:36`?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4843d589-918b-4ab0-95fb-ca6a5ce60e13.png`

**The connection succeeded and Tomcat started, but the screenshot doesn't show a successful broadcast subscription.**

After `04:09:36`, the bottom of the screenshot shows:

```text
04:09:38.877 … RabbitMQ connection and channel pool are ready
04:09:38.886 … Server startup in [2293] milliseconds
```

The subscription line is still missing:

```text
Leaderboard backplane subscribed: queue=...
```

Tomcat and the RabbitMQ connection are working. **The broadcast subscription still needs checking.**

In IntelliJ, open **server-v2's `ApplicationLifecycleListener.java`** and confirm that `manager.start();` is immediately followed by:

```java
// Subscribe to leaderboard updates using the shared connection manager.
subscriber = new LeaderboardSubscriber(manager);
subscriber.start();
```

If it's missing, add it, then **package, upload the WAR, stop Tomcat, replace the WAR, and start Tomcat**.

If it's already there, run this in the current EC2 window to filter the log:

```bash
grep -nE 'Leaderboard|subscription|SEVERE|Exception' /opt/tomcat9/logs/catalina.out | tail -n 30
```

Send the output. **Hold off on using this screenshot as evidence for screenshot 09, and keep the consumer stopped.**

> It's there. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-ee022bf1-541b-4103-8a46-9349ffd662b7.png`

Yes, the screenshot shows the correct location and code. **Leave that section as it is.**

Check the server log in **the ingest server terminal**, with this prompt:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

Run:

```bash
grep -nE 'Leaderboard|subscription|SEVERE|Exception' /opt/tomcat9/logs/catalina.out | tail -n 30
```

This filters for broadcast subscription messages and exceptions.

Then run:

```bash
curl -i --max-time 10 http://localhost:8080/ski-servlet-v2/health
```

Send both outputs. **Let me know if the first command produces no output.** We can then check whether the uploaded WAR includes the latest code. Keep Tomcat running and the consumer stopped for now.

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d64008b2-0d96-45b8-9257-cba223251427.png`

The screenshot shows that **HTTP is working, returning `200` and `{"status":"UP"}`**.

The filtered `Address already in use` message is from **October 16**, so it doesn't tell us what happened during this deployment. We still haven't seen a successful broadcast subscription.

Check **whether the code actually deployed on the server includes the subscriber**. In the current `ip-172-31-34-32` window, run these in order:

**1. Check whether the new class was deployed**

```bash
ls -l /opt/tomcat9/webapps/ski-servlet-v2/WEB-INF/classes/messaging/LeaderboardSubscriber.class
```

**2. Check whether the deployed startup listener calls the subscriber**

```bash
javap -c -classpath /opt/tomcat9/webapps/ski-servlet-v2/WEB-INF/classes messaging.ApplicationLifecycleListener | grep -A 8 -B 4 LeaderboardSubscriber
```

This inspects the compiled code without changing anything on the server. Expect output like:

```text
messaging/LeaderboardSubscriber
messaging/LeaderboardSubscriber.start
```

**3. Filter the log for relevant messages from this startup onward**

```bash
sed -n '/08-Nov-2026 04:09:36/,$p' /opt/tomcat9/logs/catalina.out | grep -E 'Leaderboard|subscription|SEVERE|Exception'
```

Send the output of all three commands, including which ones produced no output. This will help distinguish **an outdated deployed WAR** from **a problem after subscription startup**. You can leave the current deployment running for now.

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-1500200c-0a96-4e0a-a88c-74e1f7f57aa3.png`

The cause is clear. **The application currently deployed on the server is missing `LeaderboardSubscriber.class`**, so it never started the broadcast subscription. Rebuild and upload the latest WAR.

The message `javap: command not found` means the inspection tool is unavailable on the server. You can proceed without installing it.

**1. Rebuild server-v2 in IntelliJ**

In the Maven panel on the right, find **`ski-servlet`**.

1. Double-click `clean` and wait for it to succeed.
2. Double-click `package` and wait for `BUILD SUCCESS`.

This generates a fresh WAR.

**2. Verify the WAR contains the subscriber in a new Mac terminal**

The prompt should read `zixin@Mac … %`. Run:

```bash
unzip -l /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war | grep LeaderboardSubscriber
```

You should see something like:

```text
WEB-INF/classes/messaging/LeaderboardSubscriber.class
```

You may also see `LeaderboardSubscriber$1.class`. That's normal.

**If there is no output, send me the result before uploading.**

**3. Upload from your Mac once you've confirmed the class is present**

If the ingestion server's public IP is still `44.244.250.91`, run:

```bash
scp -o ConnectTimeout=10 -i /Users/zixin/.ssh/cs6650-lab1-key.pem /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war ec2-user@44.244.250.91:/tmp/ski-servlet-v2.war
```

**4. Switch back to the ingestion server terminal and deploy**

The prompt should look like this:

```text
[ec2-user@ip-172-31-34-32 ~]$
```

Run these commands in order:

```bash
/opt/tomcat9/bin/shutdown.sh
```

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

Once the second command returns **no output**, run:

```bash
sudo install -o ec2-user -g ec2-user -m 644 /tmp/ski-servlet-v2.war /opt/tomcat9/webapps/ski-servlet-v2.war
```

```bash
/opt/tomcat9/bin/startup.sh
```

Wait ten seconds, then check:

```bash
ls -l /opt/tomcat9/webapps/ski-servlet-v2/WEB-INF/classes/messaging/LeaderboardSubscriber.class
```

```bash
tail -n 60 /opt/tomcat9/logs/catalina.out
```

Look for this line:

```text
Leaderboard backplane subscribed: queue=...
```

Leave the consumer stopped while you verify the deployment.

> I'd just completed the step in screenshot 3 when EC2 disconnected. I reconnected, as shown in screenshots 1 and 2. What should I do now? How do I reconnect to both servers, and which step should I do first?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e11f8935-87fa-400a-a608-498c637d4d47.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-197f4019-f292-4c94-96be-c6fe38f99f2f.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-d7000362-32e6-4c30-bcf6-951ab4f1efd3.png`

Bring RabbitMQ back first, then reconnect to the ingestion server and resume deployment. The screenshots show new public IPs for both instances:

| Server | New public IP | Private IP |
|---|---|---|
| Ingestion server | `44.250.70.195` | `172.31.34.32` |
| RabbitMQ | `35.95.62.69` | `172.31.45.116` |

**Your code and the private addresses used between the servers can stay as they are.** Use the new public IPs in your SSH and upload commands.

**1. Connect to RabbitMQ first**

Once the AWS status checks finish, run this in a **new Mac Terminal window**:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@35.95.62.69
```

After you connect, the prompt should include:

```text
ec2-user@ip-172-31-45-116
```

Run these commands in order:

```bash
sudo systemctl start docker
```

```bash
sudo docker start skitrak-rabbitmq
```

```bash
sudo docker exec skitrak-rabbitmq rabbitmq-diagnostics -q ping
```

This output confirms RabbitMQ is back up:

```text
Ping succeeded
```

Leave this window open.

**2. Connect to the ingestion server**

Run this in **another new Mac Terminal window**:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.250.70.195
```

The prompt should include:

```text
ec2-user@ip-172-31-34-32
```

Check whether the uploaded WAR is still there:

```bash
ls -lh /tmp/ski-servlet-v2.war
```

Screenshot 3 confirms that the upload succeeded. We still need to verify that the local WAR includes the latest subscriber code. Then we'll upload it again to the new address so we deploy the current build.

Complete those two steps and send me **the RabbitMQ ping output and the ingestion server's `ls` output**. Wait before starting Tomcat or the consumer, or rebuilding the tunnel.

If SSH shows `Connection timed out`, check that the instance's security group allows inbound **SSH on port 22 from your Mac's current public IPv4 address as a `/32`** before retrying.

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8349dba6-68cc-4b23-aae7-ee337fbdfa8a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bcf13922-7c20-4671-80fe-225f4a4dcf90.png`

Both servers are connected again.

- **RabbitMQ** returned `Ping succeeded` and is running normally.
- **The ingestion server** is reachable. The temporary file `/tmp/ski-servlet-v2.war` is gone, so you'll need to upload it again. Your local code is still on your Mac.

**1. Check the local WAR in a new Mac terminal**

Open a new window, confirm that the prompt is `zixin@Mac … %`, and run:

```bash
unzip -l /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war | grep LeaderboardSubscriber
```

You should see:

```text
WEB-INF/classes/messaging/LeaderboardSubscriber.class
```

If the class is missing, go back to IntelliJ, run **ski-servlet → clean → package**, and check again.

**2. Upload from the same Mac window**

Once you've confirmed the WAR contains the class, run:

```bash
scp -o ConnectTimeout=10 -i /Users/zixin/.ssh/cs6650-lab1-key.pem /Users/zixin/Downloads/6650/assignment2/server-v2/target/ski-servlet-1.0-SNAPSHOT.war ec2-user@44.250.70.195:/tmp/ski-servlet-v2.war
```

Continue once the transfer reaches `100%`.

**3. Switch back to the ingestion server terminal**

This is the window in the second screenshot. Its prompt includes `ip-172-31-34-32`.

First check whether Tomcat is running:

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

If there is a process, run:

```bash
/opt/tomcat9/bin/shutdown.sh
```

Check again and confirm that there is no output:

```bash
pgrep -af 'org.apache.catalina.startup.Bootstrap'
```

**4. Deploy and start Tomcat**

```bash
sudo install -o ec2-user -g ec2-user -m 644 /tmp/ski-servlet-v2.war /opt/tomcat9/webapps/ski-servlet-v2.war
```

```bash
/opt/tomcat9/bin/startup.sh
```

Wait ten seconds, then run:

```bash
tail -n 60 /opt/tomcat9/logs/catalina.out
```

Look for this line:

```text
Leaderboard backplane subscribed: queue=...
```

Send me the latest log output. **Keep the RabbitMQ window open and leave the consumer stopped for now.**

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-ab6db30a-53ea-4709-b6af-f64e9ef358f8.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-44c47fd6-abe8-47a5-b915-2483237e61a7.png`

Yes, **the broadcast subscription succeeded!** The bottom of the second screenshot shows:

```text
04:49:10.737 … RabbitMQ connection and channel pool are ready
04:49:15.191 … Leaderboard backplane subscribed: queue=amq.gen-...
```

The earlier `subscription is not ready` message came from the first attempt, before the connection was ready. The retry succeeded five seconds later.

Save the **second screenshot** as:

```text
/Users/zixin/Downloads/6650/assignment2/results/09-server-backplane-subscription-ready.png
```

Put it in the “Broadcast Backplane and Server Subscription” section of the report.

Next, restore the SSH tunnel that the local consumer uses, then start the consumer.

**1. Create the tunnel in a new Mac terminal**

Run this in the window with the `zixin@Mac … %` prompt:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:5673:127.0.0.1:5672 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@35.95.62.69
```

It's normal for the terminal to show no output and keep running without returning to the prompt. Leave the window open.

If you see `Address already in use`, the previous tunnel may still be using port `5673`. Press **Control+C** in the old tunnel's window, then run the new command.

**2. Start `ConsumerMain` in IntelliJ**

Verify that the run configuration still uses:

```text
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5673
RABBITMQ_VHOST=/
```

Keep the existing username and password, then click the green Run button for `ConsumerMain`.

You should see ten workers start:

```text
Consumer started: requestedThreads=10, activeWorkers=10, prefetch=10
Worker started for resorts [...]
```

**Wait before sending any lift-swipe requests.** Keep Tomcat, the tunnel, and the consumer running. Next we'll connect a WebSocket client, send two new events, and verify that the client receives the live leaderboard update from `167` to `241`.

> How do I start `ConsumerMain`? Is it in Run? Please give me detailed steps each time.
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-67aba3e2-83be-4864-af3c-cac0c3743f6a.png`

Yes, **you have the right window open, and `ConsumerMain` is already selected on the left.** I'll specify where to click and which terminal window to use in future steps.

**1. Check the environment variables**

Click the small list icon at the far right of the **Environment variables** field to open the table.

Verify these values:

| Name | Value |
|---|---|
| `RABBITMQ_HOST` | `127.0.0.1` |
| `RABBITMQ_PORT` | `5673` |
| `RABBITMQ_USERNAME` | `skitrak` |
| `RABBITMQ_PASSWORD` | Your RabbitMQ password |
| `RABBITMQ_VHOST` | `/` |
| `CONSUMER_THREADS` | `10` |
| `CONSUMER_PREFETCH` | `10` |
| `LEADERBOARD_TOP_N` | `10` |

Enter the values without quotation marks. When you've checked them, click **OK** to close the table.

The other two settings in the screenshot are already correct:

```text
Main class: app.ConsumerMain
Module: consumer
```

**2. Verify that the SSH tunnel is running**

Leave the tunnel's Mac terminal window open. It should be connected to RabbitMQ's new public IP:

```text
35.95.62.69
```

While the tunnel is running, the terminal normally displays no output and stays occupied.

**3. Click Run at the bottom of the current window**

Click the **Run** button with the green triangle, immediately to the left of `Cancel` at the bottom of the screenshot.

IntelliJ will close the configuration window and open the `ConsumerMain` run console at the bottom.

**4. Check the startup output**

You should see:

```text
Consumer started: requestedThreads=10, activeWorkers=10, prefetch=10
Worker started for resorts [1]
...
Worker started for resorts [10]
```

The workers may appear in a different order. The program keeps running, so you won't see an exit code during normal operation.

If `Consumer recovery required` keeps appearing, the connection is still down. Send me the output. Once the consumer starts successfully, keep it running and wait before sending any lift-swipe requests.

> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-0dfa2be0-b28c-4294-a297-71af47d68288.png`

<details><summary>2 previous messages</summary>

> The consumer started successfully, and the workers for all ten ski resorts are running. Next we'll connect a WebSocket test client. I'll first check which client dependencies the project already has so you can avoid installing extra tools.
>
> <details><summary>Explored a file</summary>
>
> - Read `~/Downloads/6650/assignment2/server-v2/pom.xml`
>
> </details>
</details>

Yes, **the consumer started successfully**, and all ten workers are running. Leave it running while we verify that the WebSocket client receives real leaderboard data.

We'll use a fresh test day, `77`, to keep this test separate from the earlier data for day `1`.

**1. Set up a tunnel to the ingestion server in a new Mac terminal**

Open Terminal, press **Command+N** to open a new window, and confirm the prompt is `zixin@Mac … %`.

Run:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18080:127.0.0.1:8080 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.250.70.195
```

The lack of output is normal. Leave this window open.

The tunnel forwards:

```text
Mac port 18080 → ingestion server's Tomcat port 8080
```

Keep the existing tunnel on port `5673` open for RabbitMQ.

**2. Create a WebSocket test program in IntelliJ**

Right-click **server-v2 → src → test → java**:

1. Select **New → Package** and enter `leaderboard`.
2. Right-click the new package and select **New → Java Class**.
3. Enter `RealLeaderboardWebSocketCheck`.

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/server-v2/src/test/java/leaderboard/RealLeaderboardWebSocketCheck.java
```

Paste:

```java
package leaderboard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RealLeaderboardWebSocketCheck {

    public static void main(String[] args) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                System.out.println(
                        "CONNECTED: resort=1, day=77. Send test swipes now.");

                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(
                    WebSocket webSocket,
                    CharSequence data,
                    boolean last) {

                // Reassemble messages that arrive in multiple frames.
                message.append(data);

                if (last) {
                    System.out.println("RECEIVED: " + message);
                    message.setLength(0);
                }

                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(
                    WebSocket webSocket,
                    int statusCode,
                    String reason) {

                System.out.println(
                        "CLOSED: " + statusCode + " " + reason);
                finished.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                System.err.println("WebSocket failed: " + error);
                finished.countDown();
            }
        };

        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:18080/"
                                        + "ski-servlet-v2/leaderboard/1?dayID=77"),
                        listener)
                .join();

        try {
            // Keep the connection open while test swipes are sent manually.
            finished.await(10, TimeUnit.MINUTES);
        } finally {
            socket.abort();
        }
    }
}
```

This file uses the WebSocket client bundled with Java 11 and requires no additional dependencies.

| Component | Purpose |
|---|---|
| `onOpen()` | Prints `CONNECTED` once the connection is established |
| `onText()` | Receives and prints the actual JSON sent by the server |
| `StringBuilder` | Reassembles a complete message if it arrives in multiple frames |
| `request(1)` | Requests the next callback |
| `onClose()`, `onError()` | Report disconnections or errors |
| `await()` | Keeps the connection open for up to ten minutes so you can send requests manually |

**3. Run the test client**

Click the **green triangle next to `main()`** and select:

```text
Run 'RealLeaderboardWebSocketCheck.main()'
```

If IntelliJ asks whether to stop the running `ConsumerMain`, choose the option that lets both programs run together.

This test program **requires no environment variables**. A successful connection prints:

```text
CONNECTED: resort=1, day=77. Send test swipes now.
```

**4. Send the first lift swipe from the ingestion server terminal**

Switch to the terminal whose prompt includes:

```text
ec2-user@ip-172-31-34-32
```

Run:

```bash
swipe_event_id=$(cat /proc/sys/kernel/random/uuid)
```

Then run:

```bash
curl -i --max-time 20 -X POST \
  'http://localhost:8080/ski-servlet-v2/skiers/1/seasons/2026/days/77/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":120,"liftID":1}'
```

Expect HTTP status `201`.

Back in IntelliJ, click the **`RealLeaderboardWebSocketCheck`** tab at the bottom. The live message should contain:

```json
"dayID":"77"
```

And:

```json
"top":[{"rank":1,"skierID":1001,"vertical":167}]
```

**5. Send the second swipe after you see `167`**

In the same ingestion server terminal, generate a **new event ID**:

```bash
swipe_event_id=$(cat /proc/sys/kernel/random/uuid)
```

Then send:

```bash
curl -i --max-time 20 -X POST \
  'http://localhost:8080/ski-servlet-v2/skiers/1/seasons/2026/days/77/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":121,"liftID":2}'
```

The WebSocket client should receive another leaderboard update:

```json
"top":[{"rank":1,"skierID":1001,"vertical":241}]
```

The total is `167 + 74 = 241`. This is the expected result if day `77` is unused and nobody else is sending events.

**6. Save the test evidence**

Save a screenshot of the WebSocket console showing **`CONNECTED`, `167`, and `241`** as:

```text
/Users/zixin/Downloads/6650/assignment2/results/10-websocket-real-leaderboard-pass.png
```

Put it in the “End-to-End Verification of Live Leaderboard Updates” section of the report.

This test verifies the full path:

```text
HTTP lift swipe → RabbitMQ swipe queue → consumer aggregation
→ fanout broadcast → ingestion server cache → WebSocket client
```

> This is what I got after running it. How do I fix it?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-634d432f-a5d4-4c57-b089-0026e961af83.png`

**`Connection refused`** means the test program couldn't establish a network connection. The most likely cause is that the **tunnel to Tomcat on port `18080` is down**. Leave the Java code and `ConsumerMain` as they are while you check the tunnel.

**1. Start the WebSocket tunnel in a new Mac terminal**

Open Terminal and press **Command+N**. Confirm that the prompt is:

```text
zixin@Mac … %
```

Run:

```bash
ssh -N -o ExitOnForwardFailure=yes -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 127.0.0.1:18080:127.0.0.1:8080 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.250.70.195
```

**It's normal for the terminal to show no output and stay occupied. Leave the window open.**

This command connects to the **ingestion server at `44.250.70.195`**. Keep the existing RabbitMQ tunnel on `5673` open as well.

**2. Verify the tunnel in another Mac terminal**

Press **Command+N** again and run:

```bash
curl -i --max-time 10 http://127.0.0.1:18080/ski-servlet-v2/health
```

| Result | Next step |
|---|---|
| `HTTP/1.1 200` | The tunnel and HTTP service are working. Continue to step 3. |
| `Connection refused` or a timeout | Send me the output from the tunnel window and this command before continuing. |
| `Address already in use` when starting the tunnel | Run this `curl` command. A `200` means a working tunnel is already available. |

**3. Run the WebSocket test again**

In IntelliJ, open:

```text
server-v2 → src → test → java → leaderboard
→ RealLeaderboardWebSocketCheck
```

Click the **green triangle next to `main()`** and select **Run**.

Expect:

```text
CONNECTED: resort=1, day=77. Send test swipes now.
```

Wait until you see `CONNECTED` before sending swipe requests. For now, finish checking the connection.

> It should be working now, right? What do I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-5ad12b59-27ef-4ed3-86dc-8ec6d331e424.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-dc61396f-7b00-4dd4-a3f4-eb1b1f7fc87a.png`

Yes, **the HTTP tunnel is working and the WebSocket connected successfully**:

```text
CONNECTED: resort=1, day=77. Send test swipes now.
```

Next, send two lift swipes and verify that the client receives live leaderboard totals of `167 → 241`.

**1. Keep the current programs and tunnels running**

Leave these open:

- IntelliJ's `ConsumerMain`.
- IntelliJ's `RealLeaderboardWebSocketCheck`.
- Both SSH tunnel windows.

**2. Send the first swipe from the Mac terminal in the first screenshot**

You can now send HTTP requests through the tunnel on `18080`, so use the window with the `zixin@Mac … %` prompt.

Generate an event ID:

```bash
swipe_event_id=$(uuidgen)
```

Then run:

```bash
curl -i --max-time 20 -X POST \
  'http://127.0.0.1:18080/ski-servlet-v2/skiers/1/seasons/2026/days/77/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":120,"liftID":1}'
```

Expect:

```text
HTTP/1.1 201
```

Back in IntelliJ, click the **`RealLeaderboardWebSocketCheck`** tab at the bottom. You should receive JSON containing:

```json
"top":[{"rank":1,"skierID":1001,"vertical":167}]
```

**3. Send the second swipe after you see `167`**

In the same Mac terminal, generate a **new event ID**:

```bash
swipe_event_id=$(uuidgen)
```

Then run:

```bash
curl -i --max-time 20 -X POST \
  'http://127.0.0.1:18080/ski-servlet-v2/skiers/1/seasons/2026/days/77/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":121,"liftID":2}'
```

The WebSocket client should receive:

```json
"top":[{"rank":1,"skierID":1001,"vertical":241}]
```

The total is `167 + 74 = 241`. These two expected values assume no previous records have accumulated for day `77`.

**4. Save a screenshot**

Save a screenshot of the IntelliJ console showing **`CONNECTED`, the first total of `167`, and the second total of `241`** as:

```text
/Users/zixin/Downloads/6650/assignment2/results/10-websocket-real-leaderboard-pass.png
```

Put it in the “End-to-End Verification of Live Leaderboard Updates” section of the report.

If HTTP returns `201` and the WebSocket console stays blank, check the `ConsumerMain` and WebSocket run tabs and send me both outputs before resending anything.

> Is this right now? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-9e5c4b72-2b91-400e-ba15-6485a2585680.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-c4dc9716-71d4-4c06-8606-40362fdbdd6c.png`

<details><summary>2 previous messages</summary>

> Yes, the WebSocket client received the live update from `167` to `241`, and both HTTP requests returned `201`. The end-to-end flow works. Next, we'll move the consumer from IntelliJ to a dedicated EC2 instance. First, we'll check the packaging configuration and build an executable JAR.
>
> <details><summary>Explored a file, searched the web</summary>
>
> <details><summary>Explored a file</summary>
>
> - Read `~/Downloads/6650/assignment2/consumer/pom.xml`
>
> </details>
>
> Searched the web for `https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html`
>
> </details>
</details>

Yes, **the end-to-end test passed**:

- Both HTTP requests returned `201`.
- The WebSocket client received the leaderboard for day `77`.
- The cumulative vertical increased from **167 to 241**, and the version went from **1 to 2**.

Save the first screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/10-websocket-real-leaderboard-pass.png
```

The second screenshot can document the corresponding HTTP requests. Save it as:

```text
/Users/zixin/Downloads/6650/assignment2/results/10a-websocket-test-http-requests.png
```

Put both in the “End-to-End Verification of Live Leaderboard Updates” section of the report.

Next, we'll **deploy the consumer on a dedicated EC2 instance**. First, build an executable JAR that bundles its dependencies.

**1. Edit the consumer's `pom.xml`**

In IntelliJ's project panel, open:

```text
consumer → pom.xml
```

Full path:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/pom.xml
```

Find `<build>...</build>` near the bottom of the file and replace **the entire block** with:

```xml
<build>
    <plugins>
        <!-- Compile the consumer for Java 11. -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.11.0</version>
        </plugin>

        <!-- Bundle application classes and runtime dependencies. -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.6.2</version>

            <executions>
                <execution>
                    <phase>package</phase>

                    <goals>
                        <goal>shade</goal>
                    </goals>

                    <configuration>
                        <createDependencyReducedPom>false</createDependencyReducedPom>

                        <transformers>
                            <!-- Preserve service provider registrations. -->
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>

                            <!-- Set the executable application entry point. -->
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                <mainClass>app.ConsumerMain</mainClass>
                            </transformer>
                        </transformers>

                        <filters>
                            <filter>
                                <artifact>*:*</artifact>

                                <!-- Remove signatures invalidated by repackaging. -->
                                <excludes>
                                    <exclude>META-INF/*.SF</exclude>
                                    <exclude>META-INF/*.DSA</exclude>
                                    <exclude>META-INF/*.RSA</exclude>
                                </excludes>
                            </filter>
                        </filters>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Keep the closing tag at the end of the file:

```xml
</project>
```

**2. What each setting does**

| Setting | Purpose |
|---|---|
| `maven-compiler-plugin` | Compiles with the project's existing Java 11 settings |
| `maven-shade-plugin` | Bundles the consumer code, RabbitMQ client, and Gson into one JAR |
| `<phase>package</phase>` | Runs the bundling automatically during Maven's `package` phase |
| `ServicesResourceTransformer` | Merges service provider registrations from the dependencies |
| `ManifestResourceTransformer` | Sets `app.ConsumerMain` as the entry point |
| `filters` | Removes the original signature files that become invalid after repackaging |
| `createDependencyReducedPom=false` | Skips generating a separate, modified dependency POM |

Setting `Main-Class` lets the server start the program with `java -jar`. This is the configuration shown in the [official Maven Shade executable JAR example](https://maven.apache.org/plugins/maven-shade-plugin/examples/executable-jar.html).

**3. Sync Maven**

Save the file, then click the **circular double-arrow icon** at the top of the Maven panel on the right. It's the same action you used earlier:

```text
Sync All Maven Projects
```

Wait while Maven loads the dependencies and plugins.

**4. Package the consumer**

In the Maven panel on the right, expand:

```text
consumer → Lifecycle
```

Double-click **`clean`** and wait for it to succeed, then double-click **`package`**.

Select the **consumer** module in the Maven panel.

A successful build generates:

```text
/Users/zixin/Downloads/6650/assignment2/consumer/target/consumer-1.0-SNAPSHOT.jar
```

You may also see:

```text
original-consumer-1.0-SNAPSHOT.jar
```

That's the original JAR before Maven bundled the dependencies. **Deploy the JAR whose filename has no `original-` prefix.**

**5. Inspect the JAR from a Mac terminal**

Open a regular Mac terminal and run:

```bash
unzip -p /Users/zixin/Downloads/6650/assignment2/consumer/target/consumer-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

The output should include:

```text
Main-Class: app.ConsumerMain
```

Then run:

```bash
unzip -l /Users/zixin/Downloads/6650/assignment2/consumer/target/consumer-1.0-SNAPSHOT.jar | grep -E 'app/ConsumerMain.class|com/rabbitmq/client/ConnectionFactory.class|com/google/gson/Gson.class'
```

You should see all three classes, confirming that the entry point and the main dependencies are in the JAR.

At this stage, you can leave the current consumer running and skip launching the JAR or taking screenshots. Next, we'll create the consumer EC2 instance and configure it to connect to RabbitMQ over the private network.

> Yes, I can see all three classes. What do I do next?

<details><summary>2 previous messages</summary>

> The JAR is ready. Next we'll create a dedicated EC2 instance to run the consumer and connect to RabbitMQ over the private network. I'll walk through the AWS console in order and list the security group settings separately.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html`
>
> </details>
</details>

Next, create a **third EC2 instance for the consumer**. It will run the JAR you just built and connect to RabbitMQ over the private network.

The two existing servers keep their roles:

```text
Ingestion server: receives HTTP requests and maintains WebSocket connections
RabbitMQ: stores and routes messages
New consumer: accumulates vertical, computes leaderboards, and publishes updates
```

**1. Open the instance launch page**

In the AWS console:

1. Confirm that the region at the top right is **Oregon / us-west-2**.
2. Go to **EC2 → Instances**.
3. Click **Launch instances** in the upper right.

**2. Enter the basic settings**

| Field | Value |
|---|---|
| Name | `cs6650-a2-consumer` |
| Operating system | Amazon Linux |
| AMI | Amazon Linux 2023 |
| Architecture | 64-bit **x86** |
| Instance type | `t3.small` |
| Key pair | `cs6650-lab1-key` |
| Number of instances | `1` |

Start with `t3.small` to give the Java aggregation state more memory. Use load testing to see whether that's enough. The consumer can use a different instance type from the ingestion server.

You can reuse the existing key pair. [AWS's launch workflow lets you select an existing key pair](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html).

**3. Configure the network**

Click **Edit** next to **Network settings**:

| Field | Value |
|---|---|
| VPC | `vpc-043df75c63094004a` |
| Subnet | `subnet-0106b5baa51f6e3aa`, `us-west-2b` |
| Auto-assign public IP | `Enable` |

Using the same VPC allows the consumer to connect to the private RabbitMQ address:

```text
172.31.45.116:5672
```

Use the public IP to log in from your Mac. The servers use private networking for RabbitMQ traffic.

**4. Create a security group for the consumer**

Under **Firewall / Security groups**, select:

```text
Create security group
```

Enter:

```text
Security group name:
cs6650-a2-consumer-sg
```

```text
Description:
SSH access for the consumer server
```

Add one inbound rule for now:

| Type | Port | Source |
|---|---:|---|
| SSH | 22 | Your Mac's current public IPv4 address with `/32` |

To keep the console from automatically selecting IPv6 again, run this in a **regular Mac terminal**:

```bash
curl -4 https://checkip.amazonaws.com
```

Enter the returned address in the AWS **Source** field and append:

```text
/32
```

For example, if the output is `203.0.113.10`, enter:

```text
203.0.113.10/32
```

The consumer initiates the connection to RabbitMQ, so the SSH rule is sufficient for inbound traffic. Keep the default outbound rules for now.

Later, we'll add a port `5672` rule to the **RabbitMQ security group**, using the consumer's security group as the source. Referencing the security group allows traffic over the instances' private IP addresses. See the [AWS documentation on security group rules](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html).

**5. Configure storage**

Under **Configure storage**, set:

```text
20 GiB
gp3
```

Keep the other defaults for now.

**6. Launch the instance**

Check the **Summary** on the right:

```text
Number of instances: 1
AMI: Amazon Linux 2023, x86
Instance type: t3.small
Storage: 20 GiB
```

Click **Launch instance**, then click **View all instances**.

Once the instance shows `Running` and its status checks pass, select it and look for these fields under **Details**:

- Public IPv4 address
- Private IPv4 addresses

Send me a screenshot of that page. I'll use the new address to give you the full SSH command and walk you through configuring the RabbitMQ security group.

**Keep the IntelliJ consumer running for now. Stop it when you're ready to start the EC2 consumer so they don't compete for exclusive subscriptions to the queues.**

> Is the rule in screenshot 1 correct? I'll launch once you confirm.
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-6384104f-e7dc-487e-b86c-c824c2b99d4d.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-406d310f-103a-47c3-9e67-cff07b0b586d.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-4cc4250d-29d6-48a7-aa73-f9c84e7150c0.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-84786eca-67cd-45fd-b2b7-546836b3c7ea.png`

Yes, **the settings in the screenshots are correct. You can click `Launch instance`.**

The rule in screenshot 1 means:

```text
Allow SSH logins on port 22 only from the public IPv4 address 172.56.107.120
```

The rule is correct if that matches the address returned by `curl -4 https://checkip.amazonaws.com`.

The other screenshots show the expected configuration:

| Field | Current setting |
|---|---|
| Name | `cs6650-a2-consumer` |
| OS | Amazon Linux 2023, x86_64 |
| Instance type | `t3.small` |
| Key pair | `cs6650-lab1-key` |
| VPC and subnet | Same as the existing servers |
| Public IP | Enable |
| Security group | New group named `cs6650-a2-consumer-sg` |
| Storage | 20 GiB, gp3 |
| Number of instances | 1 |

Click the orange **Launch instance** button on the right, then click **View all instances** once the launch succeeds.

Select the new consumer instance and wait for the status checks to pass. Open **Details** below and send me the page showing its **public and private IPv4 addresses**. Next we'll configure RabbitMQ access and log in to the consumer server.

> I've launched it. What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-386c6059-cf2b-465a-a563-6d7862338b32.png`

The consumer instance was created successfully. It is `Running`, and its status checks are still initializing. Here are its addresses:

| Purpose | Address |
|---|---|
| Public IPv4 for logging in from your Mac | `44.249.176.138` |
| Private IPv4 | `172.31.35.42` |

First, allow it to connect to RabbitMQ. Then log in and install Java.

**1. Add an access rule for RabbitMQ**

In the AWS instance list:

1. Select **`cs6650-a2-rabbitmq`**.
2. Click the **Security** tab below.
3. Click the blue **`cs6650-a2-rabbitmq-sg`** security group name.
4. On the security group page, choose **Inbound rules → Edit inbound rules**.
5. Click **Add rule** and enter:

| Field | Value |
|---|---|
| Type | Custom TCP |
| Port range | `5672` |
| Source | Select the `cs6650-a2-consumer-sg` security group |
| Description | `AMQP from consumer server` |

Enter `cs6650-a2-consumer-sg` in the Source field and **click the matching result in the dropdown** so it appears as a selected security group.

Keep the existing rules and click **Save rules**.

This rule belongs to the **RabbitMQ security group** and allows the consumer server to reach RabbitMQ on port `5672`.

**2. Log in to the consumer server from your Mac**

Wait for the consumer instance to pass its status checks. Open Mac Terminal, press **Command+N** to open a new window, and confirm the prompt is:

```text
zixin@Mac … %
```

Run:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.249.176.138
```

On your first connection, you'll see:

```text
Are you sure you want to continue connecting...
```

Enter:

```text
yes
```

Press Return. Once connected, the prompt should include:

```text
ec2-user@ip-172-31-35-42
```

**3. Install Java 11 on the consumer server**

Run this in the **`ip-172-31-35-42`** window you just connected to:

```bash
sudo dnf install -y java-11-amazon-corretto-headless
```

After installation, run:

```bash
java -version
```

The output should show Java `11` and Corretto information.

**4. Check the private connection to RabbitMQ**

On the consumer server, run:

```bash
timeout 5 bash -c 'exec 3<>/dev/tcp/172.31.45.116/5672'
```

Immediately follow it with:

```bash
echo $?
```

This result:

```text
0
```

means the consumer can reach the RabbitMQ port over the private network. This checks network connectivity. The program will verify the username and password when it runs.

Send me **the output of `java -version` and the final exit status**. Next we'll upload the JAR, configure environment variables, and run the consumer as a system service so it stays running after you disconnect from SSH.

Keep the IntelliJ consumer running until the server is ready to start its consumer.

> Is this right?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-556526b5-356b-4366-a546-586939013237.png`

**Hold off on clicking Save rules. The screenshot shows a rule that allows the security group to reach itself on port 5672, so you're probably editing the wrong group.**

The top of the screenshot shows you're editing:

```text
sg-045bfa461476e2cb1
```

The new rule uses the same ID as its source. The intended configuration is:

```text
Security group to edit: RabbitMQ security group
Allowed source: consumer security group
Port: 5672
```

Follow these steps:

1. Click **Cancel** in the lower right to discard this edit.
2. Go back to **EC2 → Instances**.
3. Select **`cs6650-a2-rabbitmq`**.
4. Click **Security** below.
5. Click its associated **`cs6650-a2-rabbitmq-sg`** security group.
6. Click **Inbound rules → Edit inbound rules → Add rule**.
7. Add:

| Field | Value |
|---|---|
| Type | Custom TCP |
| Port range | `5672` |
| Source | Select `cs6650-a2-consumer-sg` |
| Description | `AMQP from consumer server` |

**Keep RabbitMQ's existing SSH rule and the rule that allows the ingestion server to connect.** After the addition, the page should typically show at least these three rules:

```text
22    ← your Mac's public IP/32
5672  ← ingestion server security group
5672  ← consumer security group
```

Once you're on the right page, you can send another screenshot and I'll check it.

> Is it right now?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-8e1b7d51-97d5-4dba-87a0-ffdf193f0faf.png`

Yes, **you're editing the right group, and the rules are correct. Click `Save rules` in the lower right.**

You're editing RabbitMQ security group `sg-0711d745dfad30de6`. It has these three rules:

| Port | Allowed source |
|---|---|
| `5672` | Ingestion server security group `sg-04ba9f499622a5f2b` |
| `22` | Your Mac's public IP `172.56.107.120/32` |
| `5672` | Consumer security group `sg-045bfa461476e2cb1` |

After saving, run this in a **new Mac Terminal window** to log in to the consumer server:

```bash
ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i /Users/zixin/.ssh/cs6650-lab1-key.pem ec2-user@44.249.176.138
```

Enter `yes` when prompted on your first connection. Once the prompt includes **`ip-172-31-35-42`**, run these commands in order:

```bash
sudo dnf install -y java-11-amazon-corretto-headless
```

```bash
java -version
```

Then check the private connection to RabbitMQ:

```bash
timeout 5 bash -c 'exec 3<>/dev/tcp/172.31.45.116/5672'
```

Immediately run:

```bash
echo $?
```

Expect **`0`**. Send me the Java version and the exit status. Next we'll upload the consumer JAR.

> This looks right, doesn't it? What do I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-f432fc58-a464-481c-8c67-b9324a82ba4e.png`

Yes, both checks passed:

- Java **11.0.33** is installed.
- The RabbitMQ private connection check returned **`0`**, so the network connection works.

Next, **upload the JAR and configure the credentials and startup script on the consumer server**. Wait to start the server-side consumer.

**1. Upload the JAR from a new Mac terminal**

Open Terminal, press **Command+N**, and confirm the prompt is `zixin@Mac … %`.

Run:

```bash
scp -o ConnectTimeout=10 \
  -i /Users/zixin/.ssh/cs6650-lab1-key.pem \
  /Users/zixin/Downloads/6650/assignment2/consumer/target/consumer-1.0-SNAPSHOT.jar \
  ec2-user@44.249.176.138:/home/ec2-user/consumer.jar
```

The upload is complete when you see `100%`.

**2. Return to the consumer server terminal**

Use the window in your screenshot. Its prompt should include:

```text
ec2-user@ip-172-31-35-42
```

Create the installation directory:

```bash
sudo install -d -o ec2-user -g ec2-user -m 750 /opt/skitrak-consumer
```

Install the JAR:

```bash
install -m 644 /home/ec2-user/consumer.jar /opt/skitrak-consumer/consumer.jar
```

The server will use this file when it runs the program:

```text
/opt/skitrak-consumer/consumer.jar
```

**3. Save the RabbitMQ password**

In the consumer server window, run these commands in order. **Wait for each one to finish before entering the next.**

First, set the default permissions so only the current user can read and write new files:

```bash
umask 077
```

Then run:

```bash
read -r -s -p 'RabbitMQ password: ' rabbitmq_password
```

Enter the RabbitMQ password you set for the **`skitrak` user** and press Return.

It's normal for the characters to stay hidden while you type. Use the RabbitMQ password here.

After entering it, run:

```bash
printf '\n'
```

```bash
printf '%s' "$rabbitmq_password" > /opt/skitrak-consumer/rabbitmq.password
```

```bash
unset rabbitmq_password
```

The file you just created:

```text
/opt/skitrak-consumer/rabbitmq.password
```

is for storing the password. Keep its contents out of screenshots and Git.

**4. Create the startup script**

On the consumer server, **copy and run the entire block together**, including the final `EOF` on its own line:

```bash
cat > /opt/skitrak-consumer/run.sh <<'EOF'
#!/bin/sh
set -eu

# Connect directly to RabbitMQ over the VPC network.
export RABBITMQ_HOST='172.31.45.116'
export RABBITMQ_PORT='5672'
export RABBITMQ_USERNAME='skitrak'
export RABBITMQ_PASSWORD="$(cat /opt/skitrak-consumer/rabbitmq.password)"
export RABBITMQ_VHOST='/'

# Configure consumer concurrency and leaderboard size.
export CONSUMER_THREADS='10'
export CONSUMER_PREFETCH='10'
export LEADERBOARD_TOP_N='10'

# Replace the shell with the Java process.
exec /usr/bin/java -Xms256m -Xmx1g -jar /opt/skitrak-consumer/consumer.jar
EOF
```

Then run:

```bash
chmod 700 /opt/skitrak-consumer/run.sh
```

Here's what the script does:

| Component | Purpose |
|---|---|
| `set -eu` | Stops the script if a command fails or it uses an unset variable |
| RabbitMQ configuration | Connects to the private address `172.31.45.116:5672` independently of the Mac tunnel |
| Password file | Loads the password at startup so it can stay outside the script |
| Consumer settings | Configures ten workers, the prefetch count, and the top ten entries |
| `-Xms256m -Xmx1g` | Sets the initial Java heap to 256 MiB and the maximum to 1 GiB |
| `exec` | Makes Java the main process so the system service can manage it directly |

**5. Check the files before starting the program**

Run:

```bash
sh -n /opt/skitrak-consumer/run.sh
```

No output usually means the script passed the syntax check.

Then run:

```bash
ls -lh /opt/skitrak-consumer/consumer.jar /opt/skitrak-consumer/run.sh /opt/skitrak-consumer/rabbitmq.password
```

You should see all three files, and the password file should have these permissions:

```text
-rw-------
```

**Wait before running `run.sh`.** Next we'll create the systemd service. Then we'll stop the IntelliJ consumer before starting the EC2 consumer so the two processes don't compete for exclusive subscriptions.

> Why did this happen?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-10db5eb7-d76c-4db1-913d-d596fbae56c0.png`

There are two issues:

1. You entered the example prompt `ec2-user@ip-172-31-35-42` as a command, so the shell reported `command not found`. **Use the prompt to identify the terminal window.**
2. The `/opt/skitrak-consumer` directory does not exist yet, so the JAR could not be installed there.

You're already in the correct terminal for the consumer server. Run these steps in order.

**① Create the directory**

```bash
sudo install -d -o ec2-user -g ec2-user -m 750 /opt/skitrak-consumer
```

**② Confirm the uploaded JAR exists**

```bash
ls -lh /home/ec2-user/consumer.jar
```

If the output shows the file size, continue.

**③ Install the JAR in the directory**

```bash
install -m 644 /home/ec2-user/consumer.jar /opt/skitrak-consumer/consumer.jar
```

**④ Check the result**

```bash
ls -lh /opt/skitrak-consumer/consumer.jar
```

If the file is listed, continue with **step 3, “Save the RabbitMQ password,”** in the previous reply.

If step ② reports `No such file or directory`, return to your **Mac terminal** and run the `scp` command from the previous reply to upload the JAR.

> Is this correct now? What should I do next?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-6eba0c06-d670-4549-b17c-9195909216ce.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-bf67d882-5e6e-4446-937e-efb634f26a9a.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-f53e6621-0d21-4e23-8a8a-23edb2d153d8.png`
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-826a1a76-9c87-47d4-a200-c0416bdcecf3.png`

<details><summary>2 previous messages</summary>

> The screenshots show that the JAR, password file, and startup script are in place with the right permissions. Next we'll use systemd to keep the consumer running after SSH disconnects and automatically restart it if the process exits unexpectedly.
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html`
>
> </details>
</details>

Yes. The final screenshot shows:

- The JAR is installed at `/opt/skitrak-consumer/consumer.jar`.
- The password file has permissions `-rw-------`.
- The startup script has permissions `-rwx------`.
- `sh -n` reported no errors, so the script passed its syntax check.

Next, create a **systemd service** and move the running consumer from IntelliJ to EC2.

**1. Create the service file on the consumer server**

Use the terminal whose prompt includes **`ip-172-31-35-42`**.

Copy and run **the entire block, including the final `EOF`**:

```bash
sudo tee /etc/systemd/system/skitrak-consumer.service > /dev/null <<'EOF'
[Unit]
Description=SkiTrak RabbitMQ Consumer
Wants=network-online.target
After=network-online.target

[Service]
Type=exec
User=ec2-user
Group=ec2-user
WorkingDirectory=/opt/skitrak-consumer
ExecStart=/opt/skitrak-consumer/run.sh
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
UMask=0077
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
```

This file tells systemd how to manage the consumer:

| Setting | Purpose |
|---|---|
| `User=ec2-user` | Runs Java as an unprivileged user |
| `ExecStart` | Runs the startup script you created |
| `Restart=on-failure` | Restarts the process automatically if it exits unexpectedly |
| `RestartSec=5` | Waits five seconds before restarting |
| `TimeoutStopSec=30` | Waits up to thirty seconds for the service to stop |
| `StandardOutput/StandardError` | Sends process output to the system journal |
| `WantedBy` | Works with `enable` to start the service at boot |

Automatic restarts respond to **process exits**. A separate health check is needed to detect a Java process that stays alive but can no longer process messages. [Official systemd service documentation](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html)

**2. Load the service configuration**

On the consumer server, run:

```bash
sudo systemctl daemon-reload
```

Stop the local consumer before starting the service.

**3. Stop ConsumerMain in IntelliJ**

In IntelliJ:

1. Click the **ConsumerMain** tab in the Run window at the bottom.
2. Click the **red square Stop button** in the run toolbar. Its tooltip should read `Stop 'ConsumerMain'`.
3. Wait for `Process finished…`.

Leave any other programs running.

When the local consumer stops, its in-memory totals are lost. The EC2 consumer starts with empty state, and previously acknowledged swipe messages are not automatically replayed to reconstruct the totals.

**4. Start the consumer service on EC2**

Switch back to the **`ip-172-31-35-42`** terminal and run:

```bash
sudo systemctl enable --now skitrak-consumer
```

This enables startup at boot and starts the service immediately.

Then check:

```bash
sudo systemctl status skitrak-consumer --no-pager -l
```

Expect:

```text
Active: active (running)
```

**5. Confirm the consumer connected successfully**

Run:

```bash
sudo journalctl -u skitrak-consumer -n 60 --no-pager
```

Expect:

```text
Consumer started: requestedThreads=10, activeWorkers=10, prefetch=10
Worker started for resorts [1]
...
Worker started for resorts [10]
```

The `active (running)` status confirms the process is running. **The worker startup logs confirm it connected and subscribed to the queues.**

If `Consumer recovery required` or `Worker failed` keeps appearing, send me the logs and leave the local consumer stopped.

Once it works, save a screenshot showing the service status and worker startup logs as:

```text
/Users/zixin/Downloads/6650/assignment2/results/11-consumer-ec2-service-running.png
```

Put it in the “Dedicated Consumer Deployment and Service Management” section of the report.

Next, we'll use a fresh test day to verify that **the EC2 consumer handles message consumption and aggregation without the Mac**.

> Did I stop everything, or did I do it right and can I continue?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-57be4213-f360-4250-825e-dafa8f1dbe06.png`

The screenshot shows no red Stop button in the upper right and no green running indicators next to the Run tabs. **It looks like the IntelliJ programs have stopped, so you can continue.**

Tomcat and RabbitMQ on EC2 and the SSH tunnels in Terminal are independent of IntelliJ. You can rerun `RealLeaderboardWebSocketCheck` later if it stopped too.

To confirm the local consumer has stopped:

1. Click the **`ConsumerMain`** tab at the bottom.
2. Check whether the last line reads **`Process finished…`**.
3. If it's still running, stop only the program in that tab.

Then switch to the **consumer EC2 terminal**, whose prompt includes:

```text
ip-172-31-35-42
```

Run:

```bash
sudo systemctl enable --now skitrak-consumer
```

Check the service:

```bash
sudo systemctl status skitrak-consumer --no-pager -l
```

Finally, check the startup logs:

```bash
sudo journalctl -u skitrak-consumer -n 60 --no-pager
```

Expect `active (running)` and ten `Worker started for resorts [...]` entries. Send me the results of these two checks.

> Is this correct?
>
> User context
>
> Images:
> - `/var/folders/_3/gs98wwnj7gg4vfbwvpzkj1d80000gn/T/codex-clipboard-e6925d7c-4d83-4c9f-ae37-2b5f068de4ff.png`

Yes, **the consumer is deployed on EC2 and running under systemd**:

- `active (running)` shows that the Java process is running.
- `enabled` shows that it is configured to start at boot.
- Ten `Worker started` entries show that it subscribed to the queues for all ten ski resorts.

Save this screenshot as:

```text
/Users/zixin/Downloads/6650/assignment2/results/11-consumer-ec2-service-running.png
```

Put it in the “Dedicated Consumer Deployment and Service Management” section of the report.

Next, verify that **the EC2 consumer can deliver live leaderboard updates**.

**1. Change the test day in the WebSocket test program**

In IntelliJ, open:

```text
server-v2 → src → test → java → leaderboard
→ RealLeaderboardWebSocketCheck
```

Change both occurrences of `77` to `78`:

```java
"CONNECTED: resort=1, day=78. Send test swipes now."
```

And update the connection URL:

```java
"ski-servlet-v2/leaderboard/1?dayID=78"
```

Using a fresh day avoids reusing an earlier leaderboard from the server cache.

**2. Run the WebSocket test**

Keep the SSH tunnel on port `18080` open.

In `RealLeaderboardWebSocketCheck`, click the **green triangle next to `main()` → Run**.

Expect:

```text
CONNECTED: resort=1, day=78. Send test swipes now.
```

**Leave IntelliJ's `ConsumerMain` stopped.** The EC2 consumer now handles the messages.

**3. Send the first request from a regular Mac terminal**

Confirm the prompt is `zixin@Mac … %`, then run:

```bash
swipe_event_id=$(uuidgen)
```

```bash
curl -i --max-time 20 -X POST \
  'http://127.0.0.1:18080/ski-servlet-v2/skiers/1/seasons/2026/days/78/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":120,"liftID":1}'
```

Expect HTTP `201` and a WebSocket update showing cumulative vertical of **167**.

**4. Send the second request after seeing 167**

In the same Mac terminal, run:

```bash
swipe_event_id=$(uuidgen)
```

```bash
curl -i --max-time 20 -X POST \
  'http://127.0.0.1:18080/ski-servlet-v2/skiers/1/seasons/2026/days/78/skiers/1001' \
  -H 'Content-Type: application/json' \
  -H "X-Event-ID: $swipe_event_id" \
  --data '{"time":121,"liftID":2}'
```

The WebSocket client should receive another update showing cumulative vertical of **241**.

**5. Check the EC2 consumer logs**

In the consumer server terminal **`ip-172-31-35-42`**, run:

```bash
sudo journalctl -u skitrak-consumer -n 30 --no-pager
```

Expect these entries for day `78`:

```text
BACKPLANE confirmed: resort=1 day=78 version=1 ...
BACKPLANE confirmed: resort=1 day=78 version=2 ...
```

Once the test passes, save:

| Evidence | Filename |
|---|---|
| WebSocket updates from `167` to `241` for day 78 | `12-ec2-consumer-websocket-pass.png` |
| Corresponding publication logs from the EC2 consumer | `12a-ec2-consumer-backplane-log.png` |

Save the screenshots in the project's `results` directory and put them in the “End-to-End Verification of the Dedicated EC2 Consumer” section of the report.
