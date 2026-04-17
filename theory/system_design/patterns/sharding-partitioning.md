# Database Sharding & Partitioning

## What It Is
**Partitioning** splits a large dataset into smaller, more manageable pieces. **Sharding** is horizontal partitioning across multiple database servers. Each shard holds a subset of the data and operates independently.

## Why It Matters
When a single database server can't handle the load (storage, read QPS, write QPS), sharding is the primary horizontal scaling strategy for databases.

## Partitioning Strategies

### Horizontal Partitioning (Sharding)
Split rows across multiple databases. Each shard has the same schema but different data.
- Example: Users with ID 1-1M on Shard 1, 1M-2M on Shard 2.

### Vertical Partitioning
Split columns across different databases. Each partition holds a subset of columns.
- Example: User profile data in DB1, user activity logs in DB2.
- Essentially decomposing a monolithic DB into domain-specific DBs.

### Functional Partitioning (Federation)
Split by function/domain. Each microservice gets its own database.
- Example: Users DB, Orders DB, Products DB.

## Sharding Strategies

### 1. Range-Based Sharding
Assign rows to shards based on value ranges of the shard key.
- Example: `user_id 1-100K → Shard 1`, `100K-200K → Shard 2`
- **Pros**: Simple, range queries are efficient on a single shard
- **Cons**: Hot spots if data isn't uniformly distributed (e.g., new users all go to the latest shard)

### 2. Hash-Based Sharding
`shard = hash(shard_key) % num_shards`
- **Pros**: Uniform distribution
- **Cons**: Range queries scatter across all shards; adding/removing shards requires rehashing

Note: Consistent hashing is a separate technique — not "hash % N" plus mitigation. It uses a fixed ring with `hash(key)` → walk clockwise to find the owning node. Node changes affect only ~1/N of keys.

### 3. Directory-Based Sharding
A lookup service maps each key to its shard. Maximum flexibility.
- **Pros**: Any mapping strategy, easy rebalancing
- **Cons**: Lookup service is a SPOF and bottleneck; extra network hop

### 4. Geo-Based Sharding
Shard by geographic region. EU users on EU shard, US on US shard.
- **Pros**: Data locality for compliance (GDPR) and latency
- **Cons**: Cross-region queries are expensive; uneven distribution

## Choosing a Shard Key
This is the most critical decision. A good shard key:
- Has **high cardinality** (many distinct values)
- Distributes **writes evenly** across shards
- Supports **common query patterns** (queries hit one shard, not all)
- Is **immutable** (changing the key requires moving data)

Bad shard key examples: country code (skewed), boolean fields, timestamps alone (hot shard for "now").

## Cross-Shard Operations

### Cross-Shard Queries
If a query needs data from multiple shards, you must scatter-gather (query all shards, merge results). This is expensive and slow.

### Cross-Shard Joins
Not natively supported. Solutions:
- Denormalize data (store redundant copies)
- Application-level joins (fetch from each shard, join in code)
- Maintain a reference/lookup table

### Distributed Transactions
ACID across shards is hard. Options:
- **2-Phase Commit (2PC)**: Coordinator asks all shards to prepare, then commit. Slow, blocking if coordinator fails.
- **Saga Pattern**: Chain of local transactions with compensating actions on failure. Eventual consistency.
- **Avoid them**: Design schema so transactions stay within a single shard.

## Rebalancing Shards
When shards become uneven (hot spots, growth):
- **Consistent hashing with virtual nodes**: Minimizes data movement
- **Dynamic splitting**: Split a hot shard into two. Requires reshuffling.
- **Directory update**: If directory-based, just update the mapping.

## Trade-offs of Sharding
| Benefit | Cost |
|---------|------|
| Horizontal write scaling | Increased operational complexity |
| Data locality | Cross-shard queries are expensive |
| Fault isolation (one shard down ≠ total outage) | Joins become application-level |
| Independent scaling per shard | Rebalancing is painful |

## When NOT to Shard
- Read replicas can handle the read load
- Vertical scaling (bigger machine) is still viable
- Caching can absorb the load
- Data fits on one machine

**Rule of thumb**: Avoid sharding as long as possible. It's a one-way door that adds permanent complexity.

## Possible Interview Questions
1. "Your users table has 2 billion rows and writes are slowing down. What do you do?"
2. "How would you choose a shard key for an e-commerce orders table?"
3. "A single shard is getting 10x the traffic of others. How do you fix this?"
4. "How do you handle a query that needs to join data from two different shards?"
5. "What happens when you need to add a new shard? How do you minimize data movement?"
6. "Compare range-based vs hash-based sharding for a time-series workload."
7. "How would you design the database layer for a multi-tenant SaaS application?"
