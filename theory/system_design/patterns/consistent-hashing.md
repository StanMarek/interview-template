# Consistent Hashing

## What It Is
Consistent hashing is a distributed hashing scheme that minimizes the number of keys that need to be remapped when the hash table is resized (nodes added/removed). In a naive `hash(key) % N` approach, changing N reshuffles almost everything. Consistent hashing reshuffles only `K/N` keys on average (K = total keys, N = total nodes).

## Why It Matters
Essential for distributed caches, sharded databases, and any system where data must be distributed across nodes that can dynamically join and leave.

## How It Works

### The Hash Ring
1. Map both **nodes** and **keys** onto a circular hash space (0 to 2^32 - 1).
2. Each node is placed on the ring at `hash(node_id)`.
3. Each key is placed at `hash(key)`.
4. A key is assigned to the **first node encountered clockwise** from its position on the ring.

### Adding a Node
Only keys between the new node and its predecessor (counter-clockwise neighbor) need to move. All other keys stay put.

### Removing a Node
Only keys that were assigned to the removed node need to move to the next node clockwise.

## The Problem with Basic Consistent Hashing
With few physical nodes, the hash ring is unevenly divided → some nodes get disproportionately more keys (**load imbalance**).

## Virtual Nodes (Vnodes)
Instead of placing each physical node at one point on the ring, place it at **many points** (virtual nodes). Each physical node gets 100-200 vnodes spread around the ring.

- **Result**: Much more even distribution
- **Adding a node**: Its vnodes are spread around the ring, so it absorbs a small fraction of keys from many nodes rather than a large chunk from one node
- **Heterogeneous hardware**: Give more powerful nodes more vnodes

## Consistent Hashing vs Rendezvous Hashing

| Feature | Consistent Hashing | Rendezvous (HRW) Hashing |
|---------|-------------------|--------------------------|
| Mechanism | Hash ring with vnodes | For each key, compute hash with every node, pick highest |
| Complexity per lookup | O(log N) with sorted ring | O(N) — must check all nodes |
| Memory | O(N × vnodes) | O(N) |
| Balance | Good with vnodes | Naturally balanced |
| Use cases | Caching (Memcached), databases | CDN routing, small node counts |

## Real-World Uses
- **Amazon DynamoDB**: Consistent hashing for partition assignment
- **Apache Cassandra**: Token ring for data distribution
- **Memcached clients**: ketama consistent hashing for cache key distribution
- **CDNs**: Route requests to the closest/best edge server
- **Load balancers**: Consistent hash-based routing for sticky sessions without session stores

## Implementation Sketch

```
ring = sorted_map<hash_value, node_id>

function get_node(key):
    h = hash(key)
    return ring.ceiling(h)  // first node ≥ h, wrapping around

function add_node(node):
    for i in range(num_vnodes):
        ring.insert(hash(node + ":" + i), node)

function remove_node(node):
    for i in range(num_vnodes):
        ring.remove(hash(node + ":" + i))
```

## Bounded-Load Consistent Hashing
Google's refinement: set a max load per node (e.g., 1.25× average). If the target node is at capacity, overflow to the next node on the ring. Ensures no single node gets overloaded even with skewed key access patterns.

## Possible Interview Questions
1. "Why can't you just use `hash(key) % N` for distributing cache keys?"
2. "Explain how consistent hashing handles adding and removing nodes."
3. "What are virtual nodes and why are they important?"
4. "How would you handle a hot key that always maps to the same node?"
5. "Design a distributed cache using consistent hashing."
6. "How does consistent hashing relate to data rebalancing in a distributed database?"
