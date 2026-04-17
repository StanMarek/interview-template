# DNS (Domain Name System)

## What It Is
DNS translates human-readable domain names (example.com) into IP addresses (93.184.216.34). It's the phone book of the internet and the first step in almost every web request.

## DNS Resolution Flow
```
Browser cache → OS cache → Resolver (ISP) → Root NS → TLD NS (.com) → Authoritative NS → IP address
```

1. **Recursive resolver** (ISP or 8.8.8.8): Receives the query, does the work of iterating through the hierarchy
2. **Root nameserver**: Directs to TLD nameserver (13 root server clusters globally)
3. **TLD nameserver**: Directs to authoritative nameserver for the domain
4. **Authoritative nameserver**: Returns the actual DNS record

## DNS Record Types

| Type | Purpose | Example |
|------|---------|---------|
| **A** | Domain → IPv4 address | `example.com → 93.184.216.34` |
| **AAAA** | Domain → IPv6 address | `example.com → 2606:2800:...` |
| **CNAME** | Domain → another domain (alias) | `www.example.com → example.com` |
| **MX** | Mail server for a domain | `example.com → mail.example.com` |
| **NS** | Nameserver for a domain | `example.com → ns1.dnsprovider.com` |
| **TXT** | Arbitrary text (SPF, DKIM, verification) | `"v=spf1 include:_spf.google.com"` |
| **SRV** | Service location (port + priority) | `_sip._tcp.example.com → sip.example.com:5060` |
| **PTR** | Reverse DNS (IP → domain) | `34.216.184.93 → example.com` |

## TTL (Time-to-Live)
How long a DNS record is cached before re-querying the authoritative server.
- **Low TTL (30-300s)**: Fast failover, DNS-based load balancing. More DNS queries.
- **High TTL (3600-86400s)**: Fewer DNS queries, faster resolution. Slow to update.
- **Tip**: Before a migration, lower TTL days in advance. After migration, raise it back.

## DNS in System Design

### DNS-Based Load Balancing
Return multiple A records (round-robin). Client picks one. Coarse-grained, no health awareness.
- **Weighted routing**: Route % of traffic to different IPs (used for canary deploys)
- **Latency-based routing**: Resolve to the closest/fastest datacenter
- **Geo routing**: Resolve based on client's geographic location
- **Failover routing**: Primary/secondary. Switch on health check failure.

### Anycast
Multiple servers advertise the same IP address via BGP. Network routes to the nearest one. Used by CDNs and DNS providers (Cloudflare, Google DNS) for global distribution.

### Encrypted DNS
**DNS-over-HTTPS (DoH, RFC 8484)** — DNS over HTTPS (port 443). **DNS-over-TLS (DoT, RFC 7858)** — DNS over TLS (port 853). Privacy + integrity; DoH is dominant in browsers (Firefox, Chrome).

### Route 53 Routing Policies
AWS Route 53 policies: **Simple**, **Weighted** (traffic split), **Latency** (route to lowest-latency region), **Failover** (primary/secondary with health checks), **Geolocation** (continent/country), **Geoproximity** (with traffic-flow bias), **Multivalue answer** (up to 8 healthy records).

## DNS as a Point of Failure
DNS is critical — if DNS fails, nothing works. Mitigations:
- Multiple authoritative nameservers (minimum 2, preferably on different providers)
- DNS caching at every level (browser, OS, resolver)
- Low TTLs for failover records
- Multi-CDN DNS providers

## Managed DNS Services
| Provider | Service |
|----------|---------|
| AWS | Route 53 |
| GCP | Cloud DNS |
| Azure | Azure DNS |
| Cloudflare | Cloudflare DNS |

## Possible Interview Questions
1. "Walk me through what happens when you type google.com in a browser."
2. "How would you use DNS for traffic routing across multiple regions?"
3. "What is the role of TTL in DNS and how would you set it?"
4. "How do CDNs use DNS to route users to the nearest edge?"
5. "What is Anycast and how does it work?"
