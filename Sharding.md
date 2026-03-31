# Sharding

## 1. What is Sharding?

Sharding is a database architecture pattern that involves splitting a large dataset across multiple database instances or servers, called **shards**. Each shard holds a subset of the total data and operates as an independent database. Together, the shards make up the complete dataset.

Sharding is a form of **horizontal partitioning** — rows of a table are distributed across multiple databases rather than storing everything in a single server.

**Example:** A users table with 100 million rows could be split into 4 shards of 25 million rows each, distributed by user ID ranges.

---

## 2. How is Sharding Used?

### Sharding Strategies

- **Range-Based Sharding:** Data is distributed based on ranges of a shard key (e.g., user IDs 1–1M on Shard 1, 1M–2M on Shard 2). Simple to implement but can lead to uneven distribution (hotspots).

- **Hash-Based Sharding:** A hash function is applied to the shard key, and the result determines which shard stores the data. Provides more even distribution but makes range queries harder.

- **Directory-Based Sharding:** A lookup table maps each piece of data to a specific shard. Flexible but introduces a single point of failure and lookup overhead.

- **Geographic Sharding:** Data is partitioned by geographic region (e.g., US users on one shard, EU users on another). Reduces latency for region-specific queries and helps with data residency compliance.

### Common Use Cases

- **Large-scale web applications** (social media, e-commerce) where a single database cannot handle the volume of reads/writes.
- **Multi-tenant SaaS platforms** where each tenant's data can be isolated on a separate shard.
- **Time-series data** where data is partitioned by time intervals (e.g., one shard per month).
- **Gaming and real-time systems** requiring low-latency access to partitioned datasets.

### Technologies That Support Sharding

- **MongoDB** — built-in auto-sharding
- **MySQL / PostgreSQL** — manual sharding or via tools like Vitess (MySQL) and Citus (PostgreSQL)
- **Cassandra** — distributed by design with consistent hashing
- **Amazon DynamoDB** — automatic partitioning under the hood
- **CockroachDB / TiDB** — distributed SQL databases with automatic sharding

---

## 3. What are the Benefits of Sharding?

- **Horizontal Scalability:** Add more shards (servers) as data grows instead of upgrading a single machine (vertical scaling). This scales nearly linearly with the number of shards.

- **Improved Performance:** Queries only hit the relevant shard, reducing the dataset each server must scan. Read and write throughput increases proportionally with shard count.

- **High Availability:** A failure in one shard does not bring down the entire system. Combined with replication within each shard, this provides strong fault tolerance.

- **Reduced Index Size:** Smaller datasets per shard mean smaller indexes, which fit in memory more easily and speed up query execution.

- **Geographic Distribution:** Shards can be placed closer to users in different regions, reducing latency and meeting data residency requirements.

- **Cost Efficiency:** Multiple commodity servers are often cheaper than a single high-end machine with equivalent capacity.

---

## 4. What are the Disadvantages of Sharding?

- **Increased Complexity:** Application logic must be shard-aware. Routing queries to the correct shard, handling migrations, and managing schema changes across shards add significant operational burden.

- **Cross-Shard Queries:** Queries that span multiple shards (e.g., joins, aggregations) are expensive and complex. They require scatter-gather patterns and can negate performance gains.

- **Data Rebalancing:** When shards become unevenly loaded (hotspots) or new shards are added, data must be redistributed — a non-trivial, often disruptive operation.

- **Distributed Transactions:** Maintaining ACID guarantees across shards requires two-phase commit or similar protocols, which add latency and complexity.

- **Operational Overhead:** Monitoring, backups, failover, and upgrades must be managed per shard. The total operational surface area grows with the number of shards.

- **Referential Integrity:** Foreign key constraints across shards are not enforced by the database. The application must handle consistency.

- **Harder to Undo:** Once an application is sharded, migrating back to a single database or changing the sharding strategy is extremely difficult.

---

## 5. Key Concepts and Terminology

| Term | Definition |
|------|-----------|
| **Shard Key** | The column or attribute used to determine which shard a row belongs to. Choosing a good shard key is critical. |
| **Hotspot** | A shard that receives disproportionately more traffic than others, usually due to a poor shard key choice. |
| **Consistent Hashing** | A hashing technique that minimizes data movement when shards are added or removed. |
| **Rebalancing** | The process of redistributing data across shards to restore balance. |
| **Scatter-Gather** | A query pattern where a request is sent to all shards (scatter) and results are merged (gather). |

---

## 6. Sharding vs. Replication vs. Partitioning

| Aspect | Sharding | Replication | Partitioning |
|--------|----------|-------------|-------------|
| **Purpose** | Distribute data for scale | Duplicate data for availability | Split data within a single database |
| **Data overlap** | Each shard holds unique data | Every replica holds the same data | Each partition holds unique data |
| **Scope** | Across multiple servers | Across multiple servers | Within a single server |
| **Use together?** | Yes — shards are often replicated | Yes — replicas can be sharded | Yes — partitions can exist within shards |

---

## 7. Best Practices

- **Choose the shard key carefully.** It should have high cardinality, even distribution, and align with the most common query patterns.
- **Avoid cross-shard joins.** Design the schema so that related data lives on the same shard.
- **Plan for rebalancing from day one.** Use consistent hashing or a directory service to make adding shards less painful.
- **Monitor shard health individually.** Track query latency, disk usage, and load per shard.
- **Start without sharding if possible.** Optimize queries, add read replicas, and use caching before introducing the complexity of sharding.
- **Use a proxy or routing layer** (e.g., Vitess, ProxySQL) to abstract shard routing from application code.
