# Graph Databases

## What They Are
Databases designed to store and query data modeled as nodes (entities) and edges (relationships). They excel at traversing complex, many-to-many relationships that would require expensive joins in relational databases.

## Data Model
- **Nodes**: Entities (Person, Product, City). Have properties (key-value pairs).
- **Edges**: Relationships (FOLLOWS, PURCHASED, LIVES_IN). Have a type, direction, and optional properties (weight, timestamp).
- **Properties**: Key-value attributes on both nodes and edges.

## When to Use Graph Databases
- **Social networks**: Friends, followers, mutual connections, friend-of-friend recommendations
- **Recommendation engines**: "People who bought X also bought Y" (traversing purchase/like graphs)
- **Fraud detection**: Identifying suspicious patterns (ring of accounts, unusual transaction chains)
- **Knowledge graphs**: Linked data, ontologies (Google Knowledge Graph)
- **Network/IT infrastructure**: Mapping dependencies, impact analysis
- **Access control**: Complex permission hierarchies

## When NOT to Use
- Simple CRUD with no relationship traversal
- High-volume writes with simple access patterns (use KV or wide-column)
- Full-text search (use Elasticsearch)
- Analytics/aggregations over large datasets (use columnar/OLAP)

## Graph vs Relational for Relationships
Finding "friends of friends of friends" in SQL requires 3 self-joins on a potentially huge table — O(N³). In a graph DB, it's a 3-hop traversal — O(degree³), typically much faster because it only follows known edges rather than scanning the entire table.

## Implementations

| Database | Model | Query Language | Notes |
|----------|-------|---------------|-------|
| Neo4j | Property graph | Cypher | Most popular, ACID transactions |
| Amazon Neptune | Property graph + RDF | Gremlin, SPARQL | Managed, AWS-native |
| JanusGraph | Property graph | Gremlin | Open-source, scalable (on Cassandra/HBase) |
| Dgraph | Property graph | DQL (GraphQL-like) | Distributed, native graph |
| ArangoDB | Multi-model (graph + doc + KV) | AQL | Versatile |
| TigerGraph | Property graph | GSQL | Analytics-focused, fast for deep traversals |

## Possible Interview Questions
1. "How would you model a social network's follower graph?"
2. "When would you choose a graph database over a relational database?"
3. "How would you find mutual friends between two users efficiently?"
4. "Design a recommendation system using a graph database."
