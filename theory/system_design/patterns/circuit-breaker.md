# Circuit Breaker & Resilience Patterns

## Circuit Breaker

### What It Is
A circuit breaker monitors calls to a remote service. If failures exceed a threshold, the circuit "opens" and subsequent calls fail immediately (fast-fail) instead of waiting and timing out. After a cooldown period, it allows a few test requests through ("half-open"). If they succeed, the circuit closes. If they fail, it stays open.

### States

```
CLOSED ──(failures exceed threshold)──→ OPEN ──(timeout expires)──→ HALF-OPEN
  ↑                                                                      │
  └──────────(test requests succeed)───────────────────────────────────────┘
                                                                          │
  OPEN ←──────────(test requests fail)────────────────────────────────────┘
```

- **Closed**: Normal operation. Requests pass through. Failures are counted.
- **Open**: All requests fail immediately with a fallback response. No calls to the downstream service.
- **Half-Open**: A limited number of requests are allowed through. If they succeed, close the circuit. If they fail, reopen.

### Why It Matters
Without a circuit breaker, a failing downstream service causes cascading failures: requests pile up, threads are exhausted, timeouts cascade through the call chain, and the entire system degrades.

### Configuration Parameters
- **Failure threshold**: Number/percentage of failures to trip the circuit (e.g., 50% of last 20 calls)
- **Timeout**: How long the circuit stays open before trying half-open (e.g., 30 seconds)
- **Success threshold**: Number of successes in half-open to close the circuit (e.g., 3)

## Other Resilience Patterns

### Retry with Exponential Backoff
On transient failure, retry with increasing delays: 1s → 2s → 4s → 8s.
- **Add jitter**: Randomize the delay to prevent thundering herd (`delay = base * 2^attempt + random`)
- **Max retries**: Always set a limit (e.g., 3-5 retries)
- **Idempotency required**: Only retry operations that are safe to repeat

### Timeout
Always set timeouts on outbound calls. Without timeouts, a hung downstream service holds your threads forever.
- **Connection timeout**: Time to establish TCP connection (e.g., 1-3s)
- **Read timeout**: Time to receive response (e.g., 5-30s)
- **Overall timeout**: Total time for the entire operation including retries

### Bulkhead
Isolate resources for different operations so one failing component doesn't exhaust resources for others.
- **Thread pool isolation**: Each downstream service gets its own thread pool. If Service A's pool fills up, Service B calls are unaffected.
- **Semaphore isolation**: Limit concurrent calls per dependency. Lighter than thread pools.
- Named after ship bulkheads that contain flooding to one compartment.

### Fallback
When a service call fails (or circuit is open), return a degraded response instead of an error.
- **Cached response**: Return last known good value
- **Default response**: Return sensible defaults ("0 items in cart")
- **Graceful degradation**: Disable non-critical features (recommendations, personalization)

### Rate Limiting (Self-Protection)
Limit incoming requests to protect yourself from being overwhelmed. Complements circuit breaker (which protects you from downstream failures).

## Cascading Failure Prevention

```
Client → Service A → Service B → Service C (fails)
                                     ↑
                           Without resilience:
                           C times out → B times out → A times out → Client error
                           All thread pools exhausted → total outage

                           With resilience:
                           C fails → B circuit opens → B returns fallback → A responds → Client gets degraded response
```

## Implementations
| Library | Language | Notes |
|---------|----------|-------|
| Resilience4j | Java | Modern Hystrix replacement; functional/decorator API, Java 8+, Spring Boot 3 integration |
| Polly | .NET | All resilience patterns; pipeline API since v8 |
| Hystrix | Java | Netflix — in maintenance mode since 2018; do NOT use for new projects (Netflix itself recommends Resilience4j) |
| Envoy / Istio / Linkerd | Service mesh | Circuit breaking at the infrastructure level — preferred when you don't want library coupling per language |
| Failsafe | Java | Lightweight alternative to Resilience4j |

## Possible Interview Questions
1. "How do you prevent a failing microservice from taking down the entire system?"
2. "Explain the circuit breaker pattern and its states."
3. "What's the difference between a circuit breaker and a retry?"
4. "How do you handle a downstream service that's slow but not completely down?"
5. "What is the bulkhead pattern and when would you use it?"
6. "Design a resilient inter-service communication layer."
