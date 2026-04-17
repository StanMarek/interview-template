# Load Balancers (L4 vs L7)

## L4 (Transport Layer) Load Balancer
Operates at TCP/UDP level. Routes based on IP address and port number without inspecting packet content.

### How It Works
- Receives TCP connection or UDP datagram
- Selects a backend server using configured algorithm
- Forwards packets (NAT or DSR mode)
- Very fast — no payload parsing

### Techniques
- **NAT mode**: LB rewrites destination IP. All traffic flows through LB. Simple but LB can be bottleneck.
- **DSR (Direct Server Return)**: LB forwards request to server; server responds directly to client, bypassing LB. Higher throughput for response-heavy traffic (video streaming).
- **IP Tunneling (IPIP)**: Encapsulates packets in an IP tunnel to the backend. Allows backends to be in different subnets.

### Use Cases
- Non-HTTP protocols (databases, gaming, custom TCP)
- Ultra-low latency requirements
- Very high throughput (millions of connections)
- TCP/UDP passthrough

## L7 (Application Layer) Load Balancer
Operates at HTTP/HTTPS level. Inspects headers, URLs, cookies, and payload to make routing decisions.

### Capabilities
- URL-based routing (`/api` → backend A, `/static` → backend B)
- Header-based routing (mobile user-agent → mobile backend)
- Cookie-based session affinity
- SSL/TLS termination
- HTTP/2 and WebSocket support
- Request/response modification (add headers, rewrite URLs)
- Authentication and authorization
- Rate limiting per endpoint
- Content-based health checks

### Use Cases
- Web applications (HTTP/HTTPS)
- Microservices routing
- A/B testing (route % of traffic to different versions)
- Canary deployments
- API gateway functionality

## Software Load Balancers

| Tool | Layer | Notes |
|------|-------|-------|
| **Nginx** | L7 (L4 with stream module) | Most popular web server + reverse proxy + LB |
| **HAProxy** | L4 + L7 | High-performance, battle-tested, rich features |
| **Envoy** | L4 + L7 | Modern, gRPC-native, service mesh (Istio) |
| **Traefik** | L7 | Cloud-native, auto-discovery, Kubernetes-native |
| **Caddy** | L7 | Automatic HTTPS, simple config |

## Cloud Load Balancers

| Provider | L4 | L7 |
|----------|----|----|
| AWS | NLB (Network Load Balancer) | ALB (Application Load Balancer) |
| GCP | Network LB | HTTP(S) LB |
| Azure | Azure Load Balancer | Application Gateway |

### AWS GWLB
**GWLB (Gateway Load Balancer)** — L3 LB for inline network appliances (firewalls, IDS/IPS). Uses GENEVE encapsulation on port 6081. Transparently inserts security appliances in traffic path.

### GCP Cloud Load Balancing (2022+ rebrand)
**Global External Application LB** (L7), **Regional External Application LB**, **Internal Application LB** (cross-region available), **Network LB** (L4).

### Azure
**Azure Front Door** — global L7 anycast LB + CDN + WAF. Analogous to CloudFront+Global ALB combo. **Application Gateway** is regional L7; **Azure LB** is L4.

## Health Check Types
- **TCP check**: Can we open a TCP connection? (L4)
- **HTTP check**: Does `GET /health` return 200? (L7)
- **gRPC check**: gRPC health checking protocol (L7)
- **Custom script**: Run a script to verify deeper health

## Possible Interview Questions
1. "When would you use an L4 vs L7 load balancer?"
2. "How do you handle WebSocket connections with a load balancer?"
3. "Explain Direct Server Return and when it's useful."
4. "How would you implement canary deployments with a load balancer?"
5. "Compare Nginx, HAProxy, and Envoy."
