# Search Engines (Elasticsearch, OpenSearch, Solr)

## What They Are
Distributed search and analytics engines built on Apache Lucene. They create inverted indexes over documents to enable fast full-text search, aggregations, and near-real-time analytics.

## The Elasticsearch / OpenSearch Split (INTERVIEW-RELEVANT)
In 2021, Elastic relicensed Elasticsearch and Kibana from Apache 2.0 to dual SSPL / Elastic License (source-available, not OSS). AWS forked the last Apache-licensed version (7.10) as **OpenSearch**, now governed by the OpenSearch Software Foundation (Linux Foundation, 2024).
- **Elasticsearch 8.x / 9.x (2025)**: Native vector search (HNSW), ES|QL query language, semantic search via ELSER, dense/sparse vector fields. In Aug 2024 Elastic added AGPLv3 as a third license option, making the OSS status debatable again.
- **OpenSearch 2.x / 3.0 (2025)**: Apache 2.0, vector engine (k-NN via FAISS/Lucene/NMSLIB), ML Commons plugin, neural search.
- **API compatibility**: Still largely compatible for basic operations; diverges on vector search, ML features, and newer APIs.
- AWS OpenSearch Service, and many cloud-managed "Elasticsearch" offerings, actually run OpenSearch.

## Core Concept: Inverted Index
Traditional index: document → words. Inverted index: word → documents.

```
"machine"  → [doc1, doc3, doc7]
"learning" → [doc1, doc2, doc7]
"search"   → [doc2, doc5]

Query "machine learning" → intersection of [doc1,doc3,doc7] ∩ [doc1,doc2,doc7] = [doc1, doc7]
```

## Elasticsearch Architecture

### Index
A collection of documents with similar characteristics. Analogous to a database table.

### Shard
An index is split into shards (Lucene indexes). Each shard can be on a different node. Sharding enables horizontal scaling.
- **Primary shard**: Handles writes, then replicates
- **Replica shard**: Copy of a primary shard. Serves reads, provides redundancy.

### Node Roles
- **Master node**: Cluster management, index creation, shard allocation
- **Data node**: Stores shards, executes searches and aggregations
- **Coordinating node**: Routes requests, merges results from shards (scatter-gather)
- **Ingest node**: Pre-processes documents before indexing

### Document Lifecycle
1. Document is indexed → written to in-memory buffer + translog (for durability)
2. **Refresh** (every 1s by default): Buffer is flushed to a new Lucene segment (now searchable). This is why ES is "near-real-time" not "real-time."
3. **Flush**: Translog is cleared, segments are committed to disk
4. **Merge**: Background process merges small segments into larger ones (reduces overhead)

## Key Features
- **Full-text search**: Tokenization, stemming, synonyms, fuzzy matching, relevance scoring (TF-IDF / BM25)
- **Aggregations**: Equivalent to SQL GROUP BY. Metrics, buckets, pipelines.
- **Geo search**: Geo-point, geo-shape queries, distance filtering
- **Autocomplete**: Completion suggester, edge n-gram tokenizer
- **Nested/parent-child**: Model relationships within documents
- **Vector search (hybrid)**: Dense/sparse vectors + HNSW indexes for semantic/RAG workloads — both ES 8+ and OpenSearch 2+ compete with dedicated vector DBs (see vector-databases.md) by combining BM25 + kNN in one query.

## Elasticsearch vs Solr

| Feature | Elasticsearch | Solr |
|---------|--------------|------|
| API | RESTful JSON | REST + XML |
| Scaling | Easier (built-in clustering) | More manual configuration |
| Real-time | Near-real-time by default | Configurable |
| Analytics | Strong aggregation framework | Faceting |
| Ecosystem | ELK Stack (Elastic, Logstash, Kibana) | Part of Apache ecosystem |
| Community | Larger, more active | Mature, stable |

## Common Use Cases
- **Product search**: E-commerce (Amazon, eBay) — faceted search, filtering, ranking
- **Log analytics**: ELK Stack — centralized logging, dashboards, alerting
- **Autocomplete / typeahead**: Prefix matching, fuzzy suggestions
- **Metrics & monitoring**: Time-series data visualization
- **Content search**: Articles, documents, knowledge bases

## Anti-Patterns
- **Using ES as primary database**: ES can lose data (no true ACID). Always have a source of truth.
- **Too many shards**: Each shard has overhead. Aim for 10-50 GB per shard.
- **Deep pagination**: `from + size` beyond 10K results is expensive. Use `search_after` or scroll API.
- **Mapping explosion**: Too many fields (>1000) per index degrades performance.

## Possible Interview Questions
1. "How would you design a search system for an e-commerce platform?"
2. "Explain how an inverted index works."
3. "How does Elasticsearch achieve near-real-time search?"
4. "How would you implement autocomplete/typeahead?"
5. "Your search cluster is slow. How do you diagnose and optimize?"
6. "How do you handle search relevance and ranking?"
