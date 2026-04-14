# CDN (Content Delivery Network)

## What It Is
A CDN is a geographically distributed network of proxy servers that cache content closer to end users. The goal is to reduce latency by serving content from a nearby edge server instead of the origin server.

## Why It Matters
For any system serving global users, a CDN is non-negotiable. It reduces latency (200ms → 20ms for static content), offloads origin traffic by 60-90%, and provides DDoS protection.

## How It Works

```
User (Tokyo) → DNS → CDN Edge (Tokyo) → [Cache HIT] → Response (20ms)
                                        → [Cache MISS] → Origin (US) → Cache + Response (200ms, then cached)
```

1. User's DNS resolves to the nearest CDN edge (via Anycast or geo-DNS)
2. Edge checks its cache for the requested content
3. **Cache hit**: Serve immediately from edge
4. **Cache miss**: Fetch from origin, cache at edge, serve to user

## Push vs Pull CDN

| Type | How Content Gets to Edge | Pros | Cons |
|------|-------------------------|------|------|
| **Pull (Origin Pull)** | Edge fetches from origin on first request | Simple; only caches what's requested | First request is slow (origin fetch) |
| **Push** | Content pre-uploaded to all edges | Consistent latency; no cold cache | Must manage uploads; storage costs for unused content |

**Most CDNs are pull-based** with optional push for critical assets.

## What CDNs Cache

| Content Type | Cacheability | Notes |
|-------------|-------------|-------|
| Static assets (JS, CSS, images) | Highly cacheable | Set long Cache-Control max-age, bust with URL versioning |
| Video/media files | Highly cacheable | HLS/DASH segments cached per edge |
| API responses | Selectively | Cache GET responses with short TTL; never cache authenticated data |
| HTML pages | Depends | Static HTML yes; dynamic/personalized pages need ESI or bypass |
| WebSocket / real-time | Not cacheable | CDN can proxy but not cache |

## Cache Invalidation at CDN

- **TTL-based**: Set `Cache-Control: max-age=3600`. Simple but stale until expiry.
- **Purge API**: Programmatically invalidate specific URLs or patterns. Fast but adds complexity.
- **Cache busting**: Append version/hash to URL (`style.v3.css` or `style.abc123.css`). New URL = new cache entry. Most reliable.
- **Stale-while-revalidate**: Serve stale content while fetching fresh copy in background.

## CDN Architecture Concepts

### PoPs (Points of Presence)
Physical locations where CDN edge servers are deployed. Major CDNs have 100-300+ PoPs globally.

### Origin Shield
An intermediate cache layer between edge servers and the origin. Reduces origin load when many edges have cache misses simultaneously.

```
Edge (Miss) → Origin Shield (Hit) → Response
Edge (Miss) → Origin Shield (Miss) → Origin → Shield Cache → Edge Cache → Response
```

### Multi-CDN
Use multiple CDN providers for redundancy and performance. Route via DNS or a meta-CDN layer.

## CDN for Dynamic Content
CDNs aren't just for static files:
- **Edge computing**: Run code at the edge (Cloudflare Workers, Lambda@Edge, Vercel Edge Functions, Fastly Compute@Edge using WASM). Personalize content without hitting origin.
- **TCP/TLS optimization**: CDN maintains persistent connections to origin; users get faster TLS handshake with nearby edge.
- **HTTP/3 over QUIC**: All major CDNs (Cloudflare, Akamai, Fastly, CloudFront) enable HTTP/3 by default. 0-RTT resumption and no head-of-line blocking drastically improve mobile/lossy-network performance.
- **Dynamic site acceleration (DSA)**: Optimized routing between edge and origin (better than public internet).

## Security Features
- **DDoS protection**: Edge network absorbs volumetric attacks
- **WAF (Web Application Firewall)**: Filter malicious requests at the edge
- **Bot protection**: Identify and block bots before they reach origin
- **SSL/TLS**: Free managed certificates, edge termination

## Possible Interview Questions
1. "How would you serve images to users globally with minimum latency?"
2. "How do you invalidate cached content across all CDN edges?"
3. "What is an origin shield and when would you use one?"
4. "How would you use a CDN for an API, not just static content?"
5. "Your CDN cache hit ratio is 40%. How do you improve it?"
6. "How do you handle personalized content with a CDN?"
