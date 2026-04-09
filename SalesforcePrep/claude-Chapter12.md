# Chapter 12: Design a Chat System — Comprehensive Summary

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              CHAT SYSTEM — FULL ARCHITECTURE                            │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  ┌──────────┐   ┌──────────┐   ┌──────────┐
  │ Mobile   │   │  Web     │   │ Mobile   │        CLIENT LAYER
  │ Client A │   │ Client A │   │ Client B │    (Multi-device support)
  └────┬─────┘   └────┬─────┘   └────┬─────┘
       │              │              │
       │  cur_max_    │  cur_max_    │  cur_max_     ← Each device tracks
       │  message_id  │  message_id  │  message_id     latest message ID
       │              │              │
       ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                               NETWORK PROTOCOL LAYER                                    │
│                                                                                         │
│   ┌─────────────────────────────┐    ┌──────────────────────────────────┐               │
│   │     HTTP / HTTPS            │    │        WebSocket (ws/wss)        │               │
│   │  (Login, Signup, Profile,   │    │  (Real-time messaging,           │               │
│   │   Settings, Friend List)    │    │   Presence updates,              │               │
│   │                             │    │   Bidirectional & Persistent)    │               │
│   │  Traditional request/       │    │                                  │               │
│   │  response model             │    │  Upgrades from HTTP handshake    │               │
│   └──────────┬──────────────────┘    └───────────────┬──────────────────┘               │
│              │                                       │                                  │
│   ┌──────────────────────────────────────────────────────────────────────┐              │
│   │  Rejected Alternatives:                                              │              │
│   │  ✗ Polling — wasteful, most responses empty                         │               │
│   │  ✗ Long Polling — sender/receiver server mismatch,                  │               │
│   │                   can't detect disconnection, still inefficient      │              │
│   └──────────────────────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────────────────────────┘
              │                                       │
              ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                   LOAD BALANCER                                         │
└──────────┬──────────────────────────────────────┬───────────────────────────────────────┘
           │                                      │
           ▼                                      ▼
┌─────────────────────────┐         ┌─────────────────────────────────────────────────────┐
│   STATELESS SERVICES    │         │              STATEFUL SERVICES                      │
│                         │         │                                                     │
│  ┌───────────────────┐  │         │  ┌─────────────────────────────────────────────┐    │
│  │   API Servers     │  │         │  │            Chat Servers                     │    │
│  │  ─────────────    │  │         │  │  ──────────────────────                     │    │
│  │  • Login/Signup   │  │         │  │  • Persistent WebSocket connections         │    │
│  │  • User Profile   │  │         │  │  • Message sending/receiving                │    │
│  │  • Settings       │  │         │  │  • Client does NOT switch servers           │    │
│  │  • Friend List    │  │         │  │    unless server goes down                  │    │
│  └───────────────────┘  │         │  └──────────────┬──────────────────────────────┘    │
│                         │         │                 │                                   │
│  ┌───────────────────┐  │         │  ┌──────────────▼──────────────────────────────┐    │
│  │ Service Discovery  │ │         │  │         Presence Servers                    │    │
│  │  (Apache Zookeeper)│◄┼─────────┼──│  • Online/Offline status                    │    │
│  │  ─────────────     │ │         │  │  • Heartbeat mechanism (every 5s)           │    │
│  │  • Registers all   │ │         │  │  • Timeout threshold (e.g. 30s)             │    │
│  │    chat servers    │ │         │  │  • Pub/Sub for status fanout                │    │
│  │  • Picks best      │ │         │  │  • Stores last_active_at in KV store        │    │
│  │    server by:      │ │         │  └─────────────────────────────────────────────┘    │
│  │    - Geo location  │ │         │                                                     │
│  │    - Capacity      │ │         └─────────────────────────────────────────────────────┘
│  └───────────────────┘  │
└─────────────────────────┘
                                              │
           ┌──────────────────────────────────┼──────────────────────────────────┐
           │                                  │                                  │
           ▼                                  ▼                                  ▼
┌────────────────────────┐  ┌──────────────────────────────┐  ┌──────────────────────────┐
│    ID GENERATOR        │  │    MESSAGE SYNC QUEUE        │  │  PUSH NOTIFICATION       │
│  ──────────────        │  │  ────────────────────        │  │  SERVERS                 │
│  Options:              │  │                              │  │  ──────────────────      │
│  1. Snowflake (global  │  │  Per-user inbox model:       │  │  • Notifies offline      │
│     64-bit IDs)        │  │                              │  │    users of new msgs     │
│  2. Local sequence     │  │  ┌────────┐ ┌────────┐       │  │  • Third-party           │
│     number generator   │  │  │User B  │ │User C  │       │  │    integration           │
│     (unique per        │  │  │Inbox   │ │Inbox   │       │  │  • APNs / FCM            │
│      channel — simpler)│  │  │        │ │        │       │  └──────────────────────────┘
│                        │  │  │msg from│ │msg from│       │
│  Requirements:         │  │  │A, D, E │ │A, B    │       │
│  • Unique              │  │  └────────┘ └────────┘       │
│  • Sortable by time    │  │                              │
│  • ✗ created_at alone  │  │  Fan-out-on-write:           │
│    (collision risk)    │  │  • Good for small groups     │
│  • ✗ MySQL auto_inc    │  │    (≤500 members)            │
│    (NoSQL limitation)  │  │  • Each client checks own    │
│                        │  │    inbox only                │
└────────────────────────┘  └──────────────────────────────┘

           │                           │
           └─────────┬─────────────────┘
                     ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                   DATA LAYER                                            │
│                                                                                         │
│   ┌───────────────────────────────────┐   ┌─────────────────────────────────────────┐   │
│   │   Relational Database (SQL)       │   │   Key-Value Store (NoSQL)               │   │
│   │   ───────────────────────         │   │   ────────────────────────              │   │
│   │   • User profiles                 │   │   • Chat history (forever)              │   │
│   │   • Settings                      │   │   • Online presence status              │   │
│   │   • User friends list             │   │   • last_active_at timestamps           │   │
│   │   • Replication + Sharding        │   │                                         │   │
│   │                                   │   │   Why KV Store:                         │   │
│   └───────────────────────────────────┘   │   • Easy horizontal scaling             │   │
│                                           │   • Low-latency access                  │   │
│                                           │   • Handles long-tail data well         │   │
│                                           │   • Precedent: HBase (FB), Cassandra    │   │
│                                           │     (Discord)                           │   │
│                                           │                                         │   │
│                                           │   Data Model:                           │   │
│                                           │   ┌─────────────────────────────────┐   │   │
│                                           │   │ 1:1 Chat                        │   │   │
│                                           │   │ PK: message_id                  │   │   │
│                                           │   │ Cols: message_from, message_to, │   │   │
│                                           │   │       content, created_at       │   │   │
│                                           │   ├─────────────────────────────────┤   │   │
│                                           │   │ Group Chat                      │   │   │
│                                           │   │ PK: (channel_id, message_id)    │   │   │
│                                           │   │ channel_id = partition key      │   │   │
│                                           │   │ Cols: user_id, content,         │   │   │
│                                           │   │       created_at                │   │   │
│                                           │   └─────────────────────────────────┘   │   │
│                                           └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            PRESENCE — HEARTBEAT & FANOUT                                │
│                                                                                         │
│   Heartbeat Timeline:                                                                   │
│   Client ──♥──♥──♥──╳─────────────────────── (30s timeout) ──► marked OFFLINE           │
│            5s  5s  5s  disconnect                                                       │
│                                                                                         │
│   Status Fanout (Pub/Sub):                                                              │
│   User A status change ──► Channel A-B ──► User B                                       │
│                        ──► Channel A-C ──► User C                                       │
│                        ──► Channel A-D ──► User D                                       │
│                                                                                         │
│   ⚠ For large groups (100K+ members): fetch on-demand, don't push                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            1:1 MESSAGE FLOW (end-to-end)                                │
│                                                                                         │
│   User A ──ws──► Chat Server 1 ──► ID Generator (get message_id)                        │
│                        │                                                                │
│                        ├──► Message Sync Queue                                          │
│                        ├──► KV Store (persist)                                          │
│                        │                                                                │
│                   ┌────┴────┐                                                           │
│              User B online?                                                             │
│              ┌────┴────┐                                                                │
│              │YES      │NO                                                              │
│              ▼         ▼                                                                │
│         Chat Server 2  Push Notification                                                │
│              │         Servers                                                          │
│              ▼                                                                          │
│         User B (via WebSocket)                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Step 1 — Understand the Problem and Establish Design Scope

The chapter opens by emphasizing that "chat system" means very different things depending on context — one-on-one messaging (WhatsApp), office group chat (Slack), or large-scale gaming voice chat (Discord). The first job is to pin down the scope through clarifying questions with the interviewer.

The sample dialogue establishes these requirements:

- Support both **1-on-1 and group chat**
- Work on **mobile and web**
- Handle **50 million DAU**
- Group size capped at **100 members**
- **Text-only** messages (no attachments)
- Max message length of **100K characters**
- Chat history stored **forever**
- **No end-to-end encryption** required
- **Push notifications** needed
- **Multiple device support** for the same account

---

## Step 2 — High-Level Design

### Communication Protocols

The chapter compares three approaches for how the server pushes messages to clients:

#### Polling
The client repeatedly asks the server for new messages at fixed intervals. Simple but wasteful; most responses are empty, burning server resources.

#### Long Polling
The client holds the connection open until the server has a new message or a timeout occurs. Better than polling, but has three drawbacks:
1. Sender and receiver may land on different stateless HTTP servers
2. The server can't reliably detect client disconnection
3. Still inefficient for users who don't chat often

#### WebSocket (Chosen Solution)
A persistent, bidirectional connection that starts as HTTP and upgrades via a handshake. This is the recommended solution. It works through firewalls (ports 80/443), supports real-time server-to-client pushes, and simplifies the design since both sending and receiving use the same connection.

> **Key insight:** While WebSocket is used for the real-time messaging path, traditional HTTP request/response is still used for everything else (login, signup, profile management, etc.).

---

### High-Level Components

The architecture is broken into three categories:

#### Stateless Services
Login, signup, user profiles — sitting behind a load balancer. These are standard request/response services, with the notable addition of a **service discovery** component that helps clients find the best chat server to connect to.

#### Stateful Service
The chat service itself, where each client maintains a persistent WebSocket connection to a specific chat server. Clients don't switch servers unless one goes down.

#### Third-Party Integration
Primarily **push notifications** for offline message delivery.

---

### Storage

The chapter distinguishes two types of data:

**Generic Data** (user profiles, settings, friends lists) → **Relational databases** with standard replication and sharding.

**Chat History Data** → **Key-value store**, because:
- The volume is massive (60 billion messages/day across FB Messenger & WhatsApp)
- Access pattern is heavily recency-biased
- Must support random access (search, jump-to-message)
- Read-to-write ratio is approximately 1:1 for 1-on-1 chat
- KV stores allow easy horizontal scaling and low-latency access
- Relational DB indexes perform poorly on long-tail data
- Real-world precedent: **HBase** (Facebook Messenger), **Cassandra** (Discord)

---

### Data Models

**1-on-1 Chat Message Table:**
- Primary key: `message_id`
- Columns: `message_from`, `message_to`, `content`, `created_at`

**Group Chat Message Table:**
- Composite primary key: `(channel_id, message_id)`
- `channel_id` is the partition key (all group queries operate within a channel)

> **Warning:** Do NOT rely on `created_at` to order messages — two messages can share a timestamp.

---

### Message ID Generation

Three options considered:

| Approach | Pros | Cons |
|----------|------|------|
| MySQL `auto_increment` | Simple | Not available in most NoSQL |
| **Snowflake** (global 64-bit) | Globally unique, time-sortable | More complex infrastructure |
| **Local sequence generator** | Simple, unique within channel | Not globally unique |

Local sequence numbers are sufficient because message ordering only needs to be maintained within a single channel.

---

## Step 3 — Design Deep Dive

### Service Discovery (Apache Zookeeper)

Zookeeper registers all available chat servers and recommends the best one for a client based on criteria like geographic proximity and server capacity.

**Flow:**
1. User A logs in
2. Load balancer routes the login request to API servers
3. Backend authenticates the user
4. Service discovery (Zookeeper) picks the best chat server
5. Server info is returned to User A
6. User A establishes a WebSocket connection to the chosen server

---

### Message Flows

#### 1-on-1 Chat Flow

1. User A sends a message to **Chat Server 1** via WebSocket
2. Chat Server 1 obtains a `message_id` from the **ID generator**
3. Message is sent to the **message sync queue**
4. Message is persisted to the **KV store**
5. **If User B is online** → message forwarded to Chat Server 2 (where B is connected)
   **If User B is offline** → push notification triggered via PN servers
6. Chat Server 2 delivers the message to User B over WebSocket

#### Multi-Device Synchronization

Each device maintains a `cur_max_message_id` variable tracking the latest message ID on that device. New messages are identified by two conditions:
- Recipient ID matches the logged-in user ID
- Message ID in the KV store exceeds `cur_max_message_id`

This makes cross-device sync straightforward — each device independently pulls new messages from the KV store.

#### Small Group Chat Flow

When User A sends a message to a group with members B and C, the message is **copied into each member's individual message sync queue** (inbox). This **fan-out-on-write** approach:

- **Pros:** Simplifies sync — each client only checks its own inbox
- **Cons:** Doesn't scale for very large groups (per-member copy cost)
- **Real-world:** WeChat uses this approach, caps groups at 500 members

---

### Online Presence

#### User Login
After WebSocket connection is established, the user's online status and `last_active_at` timestamp are saved to the KV store.

#### User Logout
Status is explicitly set to offline in the KV store.

#### User Disconnection — Heartbeat Mechanism
Rather than toggling status on every network blip (which causes the indicator to flicker), a **heartbeat mechanism** is used:
- Client sends a heartbeat event every **5 seconds**
- If the server doesn't receive a heartbeat within a threshold (e.g., **30 seconds**), the user is marked **offline**

#### Online Status Fanout (Pub/Sub)
Each friend pair maintains a channel. When User A's status changes, the event is published to all relevant channels (A-B, A-C, A-D), and friends receive the update via WebSocket.

> **Scaling concern:** For large groups (100K+ members), pushing every status change generates 100K events. Solution: **fetch status on-demand** (when entering a group or refreshing the friend list) rather than pushing it.

---

## Step 4 — Wrap Up

### Core Architecture Summary

| Component | Role |
|-----------|------|
| **Chat Servers** | Real-time messaging via WebSocket |
| **Presence Servers** | Online/offline status management |
| **Push Notification Servers** | Notify offline users |
| **Key-Value Store** | Chat history persistence |
| **API Servers** | Login, signup, profiles, settings |
| **Service Discovery (Zookeeper)** | Route clients to optimal chat servers |
| **ID Generator** | Unique, time-sortable message IDs |
| **Message Sync Queue** | Per-user inbox for message delivery |

### Additional Talking Points (If Time Remains)

1. **Media file support** — compression, cloud storage, and thumbnail generation for photos/videos
2. **End-to-end encryption** — only sender and recipient can read messages (WhatsApp model)
3. **Client-side caching** — reduce data transfer between client and server
4. **Geographically distributed caching** — Slack's Flannel architecture for better load times
5. **Error handling:**
   - Chat server crash → Zookeeper provides a new server for reconnection
   - Message resend → retry + queueing mechanisms

---

## Key Interview Tradeoffs to Remember

| Decision | Tradeoff |
|----------|----------|
| **WebSocket vs. Long Polling** | Persistent bidirectional connection vs. simpler HTTP but inefficient |
| **KV Store vs. RDBMS for chat** | Horizontal scaling + low latency vs. strong consistency + SQL queries |
| **Fan-out-on-write vs. on-read** | Fast reads (small groups) vs. scalable writes (large groups) |
| **Global vs. Local ID generator** | Global uniqueness vs. simplicity (local suffices per-channel) |
| **Push vs. Pull for presence** | Real-time updates vs. scalability for large groups |
| **Heartbeat timeout tuning** | Too short = false offlines; too long = stale presence indicators |

---

## Reference Materials

- [1] Erlang at Facebook
- [2] Messenger and WhatsApp process 60 billion messages a day (The Verge)
- [3] Long tail (Wikipedia)
- [4] The Underlying Technology of Messages (Facebook Engineering)
- [5] How Discord Stores Billions of Messages
- [6] Announcing Snowflake (Twitter Engineering)
- [7] Apache ZooKeeper
- [8] The evolution of WeChat background system
- [9] WhatsApp End-to-end encryption FAQ
- [10] Flannel: An Application-Level Edge Cache to Make Slack Scale

---

## Missing Details from Chapter 12

### Scalability Note on Single-Server Designs

The chapter notes that a single chat server holding 1 million concurrent connections is theoretically possible — Dropbox published research showing notification servers can sustain 1 million connections per machine. However, presenting a single-server design in an interview is a **red flag**, as it doesn't demonstrate understanding of fault tolerance, horizontal scaling, or data distribution. The expected answer is always a distributed design.

### Facebook's Historical HTTP Usage

Facebook originally used HTTP (not WebSocket) for sending messages and added WebSocket-style behavior on top. This illustrates that the "right" protocol evolves with scale. The chapter cites Erlang at Facebook as part of its messaging backend evolution.

### Why Long Polling Breaks in Multi-Server Environments

With long polling, the client may be holding a connection to Server A, but the incoming message arrives at Server B. Server B has no way to push it to the waiting client on Server A. This mismatch is only solvable by routing (sticky sessions) or a pub/sub layer — adding complexity that WebSocket avoids entirely.

---

## Glossary

### Apache ZooKeeper

A distributed coordination service used for service discovery, configuration management, and leader election. In the chat system, ZooKeeper maintains a registry of all available chat servers and recommends the optimal one to each connecting client based on criteria like geographic proximity and current server load. When a chat server fails, ZooKeeper detects this and routes clients to a healthy server.

**Real-world use:** Apache Kafka, Apache HBase, Hadoop clusters, and many other distributed systems use ZooKeeper for cluster coordination.

---

### APNs (Apple Push Notification Service)

Apple's proprietary notification delivery network for iOS, iPadOS, macOS, and watchOS devices. When a chat app user is offline on an Apple device, the backend sends a payload to APNs, which delivers it to the device even when the app is not running. APNs handles authentication (device tokens), message queuing, and delivery retries.

**In the chat system:** Push notification servers integrate with APNs to notify iOS users of new messages when they are not actively connected via WebSocket.

---

### Cassandra

A wide-column NoSQL key-value store designed for high write throughput and horizontal scalability across commodity hardware. Cassandra uses a peer-to-peer (masterless) architecture and supports tunable consistency. It stores data in rows identified by a partition key with optional clustering columns.

**In the chat system:** Discord uses Cassandra to store billions of chat messages. The `(channel_id, message_id)` composite key maps naturally to Cassandra's partition key + clustering column model.

---

### Channel (Chat Concept)

In the context of chat systems, a channel is a logical identifier grouping messages together — typically representing a group conversation or topic. In the data model, `channel_id` is the partition key for group chat messages, ensuring all messages within the same group are co-located for efficient retrieval.

In the presence system, a "channel" also refers to a pub/sub topic — e.g., channel A-B carries status updates between User A and User B.

---

### Fan-out-on-write

A message delivery pattern where, at write time, a single incoming message is **copied** into each recipient's individual inbox (message sync queue). When User A sends a message to a group with members B, C, and D, three copies are written — one to each member's inbox.

**Pros:** Read path is simple — each client only checks its own inbox.
**Cons:** High write amplification for large groups. A 100K-member group would require 100K writes per message.

**Real-world use:** WeChat uses fan-out-on-write and caps groups at 500 members to bound this cost. Large-scale systems (Twitter timelines) use a hybrid: fan-out-on-write for most users, fetch-on-demand for celebrities or massive groups.

---

### FCM (Firebase Cloud Messaging)

Google's notification delivery platform for Android devices (and cross-platform apps). Formerly Google Cloud Messaging (GCM). FCM allows server-side backends to send notifications to Android apps even when the app is not running.

**In the chat system:** Push notification servers use FCM to deliver new message alerts to offline Android users.

---

### Flannel

An application-level edge caching system developed by Slack to reduce message load times for clients. Flannel caches frequently accessed channel data close to clients (at the edge), reducing round-trip time and server load. It is mentioned in the chapter as an example of geographically distributed caching that can be raised as a "further discussion" point.

**Reference:** "Flannel: An Application-Level Edge Cache to Make Slack Scale" (Slack Engineering).

---

### HBase

A wide-column, distributed NoSQL store built on top of HDFS (Hadoop Distributed File System), modeled after Google's Bigtable. It supports fast random reads and writes and scales horizontally. HBase uses row keys for lookups and stores data in column families.

**In the chat system:** Facebook Messenger originally used HBase for storing chat history at scale. Its key-value semantics and horizontal scalability made it a natural fit for the high write volume and long-tail access patterns of messaging data.

---

### Heartbeat Mechanism

A technique for detecting connection liveness without waiting for the next meaningful message. In the chat system, each client sends a small "heartbeat" event to the presence server every **5 seconds** while connected. If the presence server does not receive a heartbeat within a threshold window (e.g., **30 seconds** = ~6 missed heartbeats), the user is marked **offline**.

**Why not disconnect events?** Mobile networks are unreliable — a device may lose Wi-Fi for 2 seconds and reconnect. Using a disconnect event to immediately toggle offline status causes flickering presence indicators. The heartbeat timeout provides a grace window.

---

### Key-Value Store (KV Store)

A type of NoSQL database that stores data as pairs of keys and values. Optimized for fast reads and writes by key. Supports horizontal scaling by partitioning key ranges across nodes.

**In the chat system:** Used for two purposes:
1. **Chat history** — message content indexed by `message_id` or `(channel_id, message_id)`
2. **Presence data** — `user_id → last_active_at` for online/offline tracking

**Why not relational DB for chat history?**
- Relational indexes degrade on long-tail data (billions of rows, few recent reads)
- KV stores shard naturally and support low-latency lookups at scale
- Real-world precedent: HBase (Facebook), Cassandra (Discord)

---

### Long Polling

An HTTP technique where the client sends a request and the server holds the response open until a new message is available or a timeout occurs. When the response is delivered, the client immediately reopens a new long-poll request.

**Drawbacks:**
1. In a multi-server setup, the server holding the long-poll connection may not be the one that receives the incoming message
2. The server cannot reliably detect if the client disconnected (the TCP connection may drop silently)
3. Still inefficient for users who send/receive messages infrequently (timeouts generate empty responses)

---

### Message Sync Queue

A per-user inbox (implemented as a queue or key-value bucket) that holds messages destined for a specific user. Each recipient has their own queue. When a message arrives, it is written to the queue of every intended recipient (fan-out-on-write).

Each client device tracks the latest message it has received via `cur_max_message_id`. On reconnect or polling, the device fetches all messages in its queue with an ID greater than `cur_max_message_id`, enabling seamless multi-device synchronization.

---

### Presence Server

A dedicated service that tracks and broadcasts the online/offline status of users. Presence servers:
- Maintain a persistent WebSocket connection with each connected client
- Receive heartbeat events from clients
- Store `last_active_at` timestamps in the KV store
- Publish status change events to a pub/sub system for fanout to friends

Presence servers are **stateful** — each client is pinned to a specific presence server for the duration of its session.

---

### Pub/Sub (Publish-Subscribe)

A messaging pattern where a **publisher** sends events to a named **channel/topic**, and all **subscribers** to that channel receive the event. The publisher and subscribers are decoupled — they don't need to know about each other.

**In the chat system (presence fanout):**
- Each friend pair (A-B, A-C, A-D) has a dedicated pub/sub channel
- When User A's status changes (login, logout, heartbeat timeout), the event is published to all of User A's friend channels
- Friends subscribed to those channels receive the update via WebSocket

**Scaling concern:** For a user with 100K friends (or a group with 100K members), publishing a status change generates 100K events. Solution: switch to **pull-based** presence — clients fetch status on-demand when entering a group or refreshing the friend list.

---

### Service Discovery

The mechanism by which a client finds the address of a backend server to connect to. In a distributed system, server addresses change frequently (servers are added, removed, or fail). Service discovery provides a dynamic registry that clients query to get the current list of healthy servers.

**In the chat system:** Apache ZooKeeper acts as the service discovery layer for chat servers. On login, the API server queries ZooKeeper to get the best available chat server for the user (based on load and geography), and returns that server's address to the client.

---

### Snowflake (Twitter's ID Generator)

A distributed unique ID generation system open-sourced by Twitter. A Snowflake ID is a 64-bit integer composed of:
- **41 bits** — millisecond timestamp (relative to a custom epoch)
- **10 bits** — machine/datacenter ID
- **12 bits** — sequence number (allows 4096 IDs per ms per machine)

This structure makes IDs **globally unique**, **time-sortable**, and **monotonically increasing** — all required properties for chat message IDs.

**In the chat system:** Snowflake is one option for the ID generator service. The simpler alternative — a local sequence number unique per channel — is sufficient when global uniqueness is not required.

---

### WebSocket

A network protocol (RFC 6455) that provides a persistent, full-duplex (bidirectional) communication channel over a single TCP connection. It starts with an HTTP handshake (`Upgrade: websocket`) and then switches to the WebSocket frame protocol.

**Key properties:**
- **Persistent** — connection stays open until explicitly closed
- **Bidirectional** — both client and server can send messages at any time without a request/response cycle
- **Low overhead** — no HTTP headers on each message after the initial handshake
- **Firewall-friendly** — operates on ports 80 (ws://) and 443 (wss://)

**In the chat system:** WebSocket is chosen over polling and long polling for all real-time communication: message delivery, presence updates, and typing indicators. Traditional HTTP is still used for stateless operations (login, profile, settings).

**Contrast with HTTP:** HTTP is half-duplex — only the client can initiate a request, and the server responds once. This makes real-time server-push awkward without workarounds like long polling.
