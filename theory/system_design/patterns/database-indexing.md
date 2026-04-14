# Database Indexing

## What It Is
A database index is a data structure that improves the speed of data retrieval operations at the cost of additional storage space and slower writes. Think of it like a book's index — instead of scanning every page, you look up the topic in the index and jump directly to the right page.

## Why It Matters
Without proper indexing, every query is a full table scan (O(N)). With indexing, queries can be O(log N) or O(1). Indexing decisions are a key part of database design discussions in interviews.

## Index Types

### B-Tree / B+ Tree Index
The default index type for most relational databases. Balanced tree structure where leaf nodes point to data rows.
- **Supports**: Equality (`=`), range (`<`, `>`, `BETWEEN`), sorting (`ORDER BY`), prefix matching (`LIKE 'abc%'`)
- **Does NOT support**: Full-text search, suffix matching (`LIKE '%abc'`)
- **Structure**: B+ tree — internal nodes contain keys only, leaf nodes contain keys + pointers, leaves are linked for range scans

### Hash Index
Hash table mapping key → row pointer.
- **Supports**: Equality only (`=`)
- **Does NOT support**: Range queries, sorting
- **Pros**: O(1) lookups
- **Use case**: Exact match lookups (session IDs, cache keys)

### LSM Tree (Log-Structured Merge Tree)
Used by write-optimized databases (Cassandra, RocksDB, LevelDB). Writes go to an in-memory memtable, then flush to sorted SSTables on disk. Background compaction merges SSTables.
- **Pros**: Very fast writes (sequential I/O), good compression
- **Cons**: Slower reads (may need to check multiple SSTables), write amplification during compaction

### Full-Text Index (Inverted Index)
Maps each word to the list of documents/rows containing it. Used by Elasticsearch, PostgreSQL's `tsvector`.
- **Supports**: Full-text search, relevance ranking, stemming, fuzzy matching

### Bitmap Index
A bitmap for each distinct value in a column. Bit i is 1 if row i has that value.
- **Pros**: Very efficient for low-cardinality columns (gender, status, boolean)
- **Cons**: Terrible for high-cardinality columns; expensive to update

### Spatial Index (R-Tree, Geohash)
For geographic/geometric data. Indexes bounding boxes and spatial relationships.
- **Supports**: Nearest neighbor, within radius, intersects polygon

## Composite (Multi-Column) Indexes
Index on multiple columns: `INDEX(a, b, c)`
- **Leftmost prefix rule**: This index supports queries on `(a)`, `(a, b)`, and `(a, b, c)`, but NOT `(b)` or `(c)` alone.
- **Column order matters**: Put the most selective (highest cardinality) column first, OR the column used in equality conditions before the range condition column.

## Covering Index
An index that contains all columns needed by a query. The DB can answer the query entirely from the index without accessing the table data ("index-only scan").

```sql
-- If you have INDEX(user_id, created_at, amount)
-- This query is "covered":
SELECT created_at, amount FROM orders WHERE user_id = 123;
```

## Clustered vs Non-Clustered Index

| Feature | Clustered | Non-Clustered |
|---------|-----------|---------------|
| Data order | Table rows physically sorted by the index key | Separate structure pointing to rows |
| Count per table | One (usually primary key) | Many |
| Lookup | Direct (data is in the index) | Extra hop (index → row pointer → data) |

## The Write Penalty
Every index must be updated on INSERT, UPDATE, DELETE. More indexes = slower writes. This is the fundamental trade-off.

**Rule of thumb**: Index columns that are frequently read and filtered on, avoid indexing columns that are frequently written to and rarely queried.

## Index Selectivity
`Selectivity = distinct values / total rows`. Higher selectivity = more effective index.
- High selectivity: `email` (unique), `user_id` — great to index
- Low selectivity: `gender` (M/F), `is_active` (true/false) — poor for B-tree, consider bitmap or partial index

## Partial Index (Filtered Index)
Index only rows matching a condition. Smaller, faster, less write overhead.

```sql
CREATE INDEX idx_active_users ON users(email) WHERE is_active = true;
```

## Related Storage Concepts

### Write-Ahead Log (WAL)
Every committed write is first appended to a sequential log on disk before the data pages are updated. On crash, replay the WAL to recover. Powers: PostgreSQL WAL, MySQL redo log, SQLite WAL mode, almost all modern DBs. Also the basis for replication (streaming the WAL to replicas).

### MVCC (Multi-Version Concurrency Control)
Readers never block writers and vice versa. Each row has multiple versions tagged with transaction IDs; each transaction reads the snapshot consistent with its start time. Used by PostgreSQL, MySQL InnoDB, Oracle, CockroachDB. Downsides: tombstones / dead tuples must be vacuumed (PostgreSQL `VACUUM`, MySQL purge thread).

## Possible Interview Questions
1. "Your query is slow. Walk me through how you'd diagnose and fix it with indexes."
2. "Explain the difference between B-Tree and LSM Tree indexes. When would you use each?"
3. "You have a composite index on (a, b, c). Which queries will use it?"
4. "What is a covering index and when would you use one?"
5. "Adding 5 indexes made reads faster but writes are now slow. What do you do?"
6. "How would you index a table for both full-text search and range queries on date?"
