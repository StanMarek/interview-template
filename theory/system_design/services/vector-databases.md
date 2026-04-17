# Vector Databases

## What They Are
Databases that store high-dimensional vectors (embeddings, typically 384 – 4096 floats) and answer **approximate nearest-neighbor (ANN)** queries: "give me the K vectors most similar to this query vector." They are the storage layer underneath almost every modern LLM / RAG (Retrieval-Augmented Generation) stack, semantic search, image similarity, and recommendation system.

## Why They Emerged
Traditional B-tree / inverted indexes answer exact-match or keyword queries. They cannot efficiently answer "find the 10 most semantically similar documents" when similarity means cosine distance in a 1536-dim embedding space. Full scan is O(N·D) which does not scale past a few million vectors.

## Core Concepts

### Distance Metrics
- **Cosine similarity**: Angle between vectors. Standard for text embeddings (OpenAI, Cohere).
- **Euclidean (L2)**: Straight-line distance. Common for image embeddings.
- **Dot product**: Equivalent to cosine when vectors are normalized.

### ANN Index Types
| Index | How It Works | Trade-off |
|-------|--------------|-----------|
| **FLAT (brute-force)** | Exact k-NN via linear scan | Used as baseline / ground-truth. Acceptable up to ~100K vectors; O(N×D) per query. |
| **HNSW** (Hierarchical Navigable Small World) | Multi-layer graph; greedy traversal | Fast queries, high recall; high memory (graph in RAM) |
| **IVF** (Inverted File) | Cluster vectors into cells; probe nearest cells | Lower memory, slightly lower recall; needs training |
| **IVF-PQ** (Product Quantization) | IVF + compression of residuals | Massive memory savings, some accuracy loss |
| **ScaNN** | Google's anisotropic quantization | State-of-the-art recall/latency |
| **DiskANN** | Graph index that lives on SSD | Billions of vectors on a single node |

HNSW is the de-facto default for most workloads in 2025.

### Recall vs Latency
ANN trades exactness for speed. You tune `efSearch` (HNSW) or `nprobe` (IVF) to trade off query latency vs recall@K. Recall of 0.95-0.99 is typical for production.

### Matryoshka Embeddings
**Matryoshka Representation Learning** (OpenAI text-embedding-3-large supports it) — embeddings trained so leading prefix dimensions preserve semantic similarity. Truncate from 3072 → 1536 → 768 dims for cost/quality tradeoff without re-embedding.

## Implementations

| Database | Notes |
|----------|-------|
| **Pinecone** | Managed, serverless. Industry leader for hosted use. Pod-based and serverless tiers. |
| **Weaviate** | Open source (BSD), hybrid BM25 + vector, modules for OpenAI/Cohere/HuggingFace. |
| **Qdrant** | Open source (Apache 2.0), Rust. Strong filtering + payload indexing. Fast-growing. |
| **Milvus** | Open source (Apache 2.0), distributed, GPU-accelerated. Backed by Zilliz. |
| **Chroma** | Embedded/lightweight (Apache 2.0). Dev-friendly, popular in Python / LangChain projects. |
| **pgvector** | Postgres extension. Turns any Postgres into a vector DB. IVFFlat + HNSW indexes. Often "good enough" — no new infrastructure. |
| **Elasticsearch / OpenSearch** | kNN via HNSW; hybrid BM25 + vector in a single query. |
| **Redis Stack / Valkey Search** | HNSW and flat indexes in-memory. Low-latency hybrid queries. |
| **MongoDB Atlas Vector Search** | HNSW index on MongoDB collections (2024+). |
| **Cassandra 5.0 SAI** | Vector search via storage-attached indexes. |
| **LanceDB** | Embedded, columnar (Apache Arrow / Parquet) — growing in the "data-plus-vectors" space. |
| **Vespa** | Yahoo's mature hybrid search engine; tensor + ranking framework. |

## When to Use a Dedicated Vector DB vs Add-on
**Use pgvector / Elasticsearch / Redis** if:
- You already run one of these and data volume is < 50M vectors
- You need hybrid queries (SQL filters + vector search)
- You want to avoid another piece of infrastructure

**Use a dedicated vector DB (Pinecone, Qdrant, Milvus, Weaviate)** if:
- > 100M vectors, or very high QPS with tight latency SLO
- You need advanced features: sparse+dense hybrid, re-ranking, horizontal sharding
- Pure semantic search workload with no relational access pattern

## Architecture for RAG
```
User query → Embed (LLM provider) → Vector DB ANN search (top K docs) → LLM (query + retrieved context) → Answer
```
Typical optimizations:
- **Hybrid search**: BM25 + vector → reciprocal rank fusion
- **Re-ranking**: Retrieve top 100, re-rank with cross-encoder to top 5
- **Metadata filters**: Filter by tenant, date, language before ANN
- **Chunking strategy**: Overlapping windows, semantic chunking; chunk size ~500 tokens

## Common Pitfalls
- **Stale embeddings**: Re-embedding millions of docs after changing model is expensive. Version your embeddings.
- **Filter push-down**: "Post-filter" after ANN loses results; "pre-filter" (index-aware) is the correct behavior.
- **Dimension mismatch**: Mixing embeddings from different models in one index breaks everything.
- **Cost**: Managed vector DBs get expensive at scale — pgvector on an existing Postgres is often 10x cheaper for mid-scale workloads.

## Possible Interview Questions
1. "Design the retrieval layer for a RAG-based support bot over 10M documents."
2. "When would you use pgvector instead of Pinecone?"
3. "Explain HNSW. How does `efSearch` trade recall for latency?"
4. "How would you do hybrid search (keyword + semantic) and combine results?"
5. "Your embedding model changes. How do you migrate 100M vectors without downtime?"
6. "How do vector DBs handle filtered queries efficiently?"
