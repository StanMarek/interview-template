# Microservices vs Monolith

## What They Are
- **Monolith**: Entire application is a single deployable unit. One codebase, one process, one database.
- **Microservices**: Application is decomposed into small, independently deployable services, each owning its data and communicating over the network.

## The Spectrum
It's not binary. There's a spectrum: Monolith → Modular Monolith → Macroservices → Microservices → Serverless Functions.

## Monolith

### Advantages
- Simple to develop, test, deploy, and debug
- No network latency between components (in-process calls)
- ACID transactions are straightforward
- One codebase to search and understand
- Better for small teams (< 10 engineers)

### Disadvantages
- Scaling is all-or-nothing (can't scale just one component)
- Deployment risk: one bug can take down everything
- Technology lock-in (one language/framework)
- Long build/test cycles as codebase grows
- Team coupling: changes in one area can break others

## Microservices

### Advantages
- Independent deployment and scaling per service
- Technology diversity (right tool for each job)
- Fault isolation (one service failure ≠ total outage)
- Team autonomy (each team owns their services end-to-end)
- Better for large organizations (> 50 engineers)

### Disadvantages
- Network latency and reliability (every call can fail)
- Distributed transactions are hard (saga pattern, eventual consistency)
- Operational complexity (deployment, monitoring, debugging across services)
- Data consistency across services
- Service discovery, load balancing, circuit breaking needed
- Testing is harder (integration tests across services)

## When to Choose What

| Factor | Choose Monolith | Choose Microservices |
|--------|----------------|---------------------|
| Team size | < 10 engineers | > 20-50 engineers |
| Domain complexity | Simple, well-understood | Complex, many bounded contexts |
| Scale requirements | Uniform scaling is fine | Components have very different scaling needs |
| Deployment frequency | Weekly releases are OK | Multiple deploys per day needed |
| Organization | Single team | Multiple teams needing autonomy |
| Stage | Startup/MVP | Growth/scale phase |

## Decomposition Strategies

### By Business Domain (Bounded Context)
Use Domain-Driven Design (DDD). Each microservice maps to a bounded context.
- User Service, Order Service, Payment Service, Inventory Service

### By Subdomain
Split based on core, supporting, and generic subdomains.

### Strangler Fig Pattern
Gradually replace monolith components with microservices. New features go to microservices; old features are migrated incrementally. The monolith "shrinks" over time.

## Communication Patterns

### Synchronous (Request/Response)
- **REST (JSON over HTTP/1.1 or HTTP/2)**: Simple, human-readable, browser-friendly, easy to debug. Default for public-facing APIs.
- **gRPC (Protobuf over HTTP/2)**: Binary, 5-10× faster on the wire, native streaming (server, client, bidirectional), code-generated stubs. Default for **internal service-to-service** traffic in 2025-2026. Downside: not natively browser-compatible (needs gRPC-Web + proxy).
- **GraphQL**: Client specifies exactly what data it needs; reduces over-fetching. Best for BFF/aggregation layers with heterogeneous clients. Added complexity: N+1 queries, caching, schema governance.
- **tRPC / OpenAPI-typed clients**: End-to-end type safety without the complexity of gRPC, popular in TypeScript monorepos.

### Asynchronous (Event-Driven)
- **Message Queue**: Service A publishes event → queue → Service B consumes
- **Event Bus**: Publish events to a topic, multiple services subscribe
- **Best for**: Decoupling, eventual consistency, resilience

### Hybrid
Most real systems use both: synchronous for queries (read path), asynchronous for commands/events (write path).

## Data Management
Each service owns its database (Database-per-Service pattern). This means:
- No shared databases (coupling anti-pattern)
- Joins across services happen at the application level or via data denormalization
- Distributed transactions use the Saga pattern
- Data consistency is eventual (or use event sourcing for strong guarantees)

## Key Anti-Patterns
- **Distributed Monolith**: Microservices that are tightly coupled, must be deployed together, and share databases. Worst of both worlds.
- **Chatty microservices**: Too many inter-service calls per request. N+1 problem at the network level.
- **Shared database**: Multiple services reading/writing the same tables. Defeats the purpose.
- **Too many microservices too early**: Premature decomposition before understanding domain boundaries.

## Possible Interview Questions
1. "Would you use monolith or microservices for this system? Why?"
2. "How do you handle a transaction that spans multiple microservices?"
3. "How would you migrate from a monolith to microservices?"
4. "Two services need the same data. How do you handle this?"
5. "What is a distributed monolith and how do you avoid it?"
6. "How do microservices communicate? Compare sync vs async."
