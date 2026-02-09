# Bloom Filters & Probabilistic Data Structures

## Bloom Filter

### What It Is
A space-efficient probabilistic data structure that tests whether an element is a member of a set. It can tell you:
- **"Definitely NOT in the set"** — guaranteed correct
- **"Probably in the set"** — may be a false positive

There are **no false negatives** but there are **false positives**.

### How It Works
1. A bit array of size `m`, initialized to all zeros
2. `k` independent hash functions, each mapping to a position in the bit array
3. **Insert**: Hash the element with all k functions, set those k bits to 1
4. **Query**: Hash the element with all k functions. If ALL k bits are 1 → "probably yes". If ANY bit is 0 → "definitely no"

### Parameters
- **m** (bit array size): Larger = fewer false positives, more memory
- **k** (hash functions): Optimal `k = (m/n) × ln(2)` where n = expected elements
- **False positive rate**: `(1 - e^(-kn/m))^k`

For 1% false positive rate with 1 billion elements: ~1.2 GB of memory. Storing the actual elements would require far more.

### Limitations
- Cannot delete elements (setting bits to 0 could affect other elements)
- False positive rate increases as more elements are added
- Cannot enumerate the set

### Counting Bloom Filter
Replace each bit with a counter. Supports deletion (decrement counters). Uses more memory.

## Real-World Uses
- **Database queries**: Check if a key exists before doing an expensive disk read (HBase, Cassandra, LevelDB)
- **Web crawlers**: "Have I already crawled this URL?" (billions of URLs)
- **Spam filters**: "Is this email address known spam?"
- **CDNs**: "Is this content in cache?" before checking the full cache index
- **Username availability**: Quick pre-check before querying the database

## Other Probabilistic Structures

### HyperLogLog (Cardinality Estimation)
Estimates the number of distinct elements in a dataset using ~12 KB of memory regardless of dataset size.
- Example: "How many unique visitors did our site have today?" — answer from a 12 KB structure instead of storing all visitor IDs
- Error: ~0.81% standard error
- Used by: Redis (`PFADD`, `PFCOUNT`), Presto, BigQuery

### Count-Min Sketch (Frequency Estimation)
Estimates the frequency of elements. Like a Bloom filter but counts occurrences instead of membership.
- Example: "How many times has this IP address made a request?" without storing per-IP counters
- Never underestimates, may overestimate
- Used in: Network traffic monitoring, stream processing

### Skip List
A probabilistic alternative to balanced trees. Sorted linked list with multiple levels of express lanes for O(log N) search.
- Used by: Redis sorted sets, LevelDB/RocksDB memtable

## Possible Interview Questions
1. "How would you check if a URL has been visited without storing all URLs?"
2. "Explain how a Bloom filter works and its trade-offs."
3. "How would you count unique users visiting a website with minimal memory?"
4. "When would you use a Bloom filter vs a hash set?"
5. "How does Cassandra use Bloom filters to speed up reads?"
