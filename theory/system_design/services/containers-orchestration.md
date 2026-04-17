# Containerization & Orchestration

## Containers (Docker)

### What They Are
Lightweight, isolated environments that package an application with its dependencies. Unlike VMs, containers share the host OS kernel — they're faster to start, smaller, and more efficient.

### Container vs VM
| Feature | Container | VM |
|---------|-----------|-----|
| Boot time | Seconds | Minutes |
| Size | MBs | GBs |
| Isolation | Process-level (shared kernel) | Full OS (hypervisor) |
| Overhead | Minimal | Significant (full OS per VM) |
| Density | 100s per host | 10s per host |

### Key Concepts
- **Image**: Read-only template with app code + dependencies. Built from a Dockerfile.
- **Container**: Running instance of an image.
- **Registry**: Image storage (Docker Hub, ECR, GCR, ACR).
- **Layer**: Images are built in layers; each Dockerfile instruction is a layer. Layers are cached and shared.

## Container Orchestration (Kubernetes)

### What It Is
Kubernetes (K8s) automates deployment, scaling, and management of containerized applications across a cluster of machines. Current stable releases: **1.32 "Penelope" (Dec 2024)**, **1.33 "Octarine" (Apr 2025)**, **1.34 (Aug 2025)** — Kubernetes now releases three minor versions per year. Each Kubernetes minor version receives ~14 months of patch support (12 months standard + 2-month grace).

### Notable Recent Changes
- **Dockershim removed in 1.24** — you must use a CRI runtime (containerd, CRI-O). "Docker in K8s" now means containerd under the hood.
- **PodSecurityPolicy removed in 1.25** — replaced by Pod Security Admission (baseline/restricted/privileged profiles).
- **Sidecar containers** (native) GA in 1.33 — first-class restartPolicy=Always init containers.
- **Gateway API** (successor to Ingress) GA in 1.31 for core types.
- **In-place Pod resize** beta in 1.33 — vertical scaling without restart.
- **CEL-based ValidatingAdmissionPolicy** (no webhook) GA in 1.30.

### Core Concepts

| Concept | Description |
|---------|-------------|
| **Pod** | Smallest deployable unit. One or more containers sharing network/storage. |
| **Deployment** | Manages replica sets of pods. Handles rolling updates, rollbacks. |
| **Service** | Stable network endpoint for a set of pods. Load balances across pod replicas. Types: ClusterIP, NodePort, LoadBalancer. |
| **Ingress** | HTTP(S) routing from external traffic to services. URL/host-based routing. |
| **ConfigMap / Secret** | Externalized configuration and sensitive data. |
| **Namespace** | Virtual cluster within a cluster. Isolation for teams/environments. |
| **StatefulSet** | Like Deployment but for stateful apps. Stable network identity, ordered deploy/scale. |
| **DaemonSet** | Ensures one pod per node (monitoring agents, log collectors). |
| **Job / CronJob** | Run-to-completion tasks or scheduled tasks. |
| **HPA** | Horizontal Pod Autoscaler. Scale pods based on CPU/memory/custom metrics. |
| **PersistentVolume (PV)** | Storage abstraction. Decouples storage from pod lifecycle. |

### Kubernetes Architecture
- **Control plane**: API Server (entry point), etcd (state store, v3.5/3.6), Scheduler (pod placement), Controller Manager (desired state reconciliation)
- **Worker nodes**: kubelet (node agent), `kube-proxy` (manages Service → Pod traffic via iptables/IPVS/userspace; eBPF-based CNIs like **Cilium** can replace kube-proxy entirely (`kube-proxy-free` mode) and add L3-L7 policy + observability), container runtime (containerd / CRI-O — **NOT Docker directly since 1.24**)

### Deployment Strategies
| Strategy | How It Works | Risk |
|----------|-------------|------|
| **Rolling update** | Gradually replace old pods with new | Slow rollback if issues |
| **Blue-Green** | Run two identical environments; switch traffic | Requires 2x resources |
| **Canary** | Route small % of traffic to new version | More complex routing |
| **Recreate** | Kill all old pods, start new | Downtime during transition |

### Service Mesh (Istio, Linkerd)
Adds observability, security (mTLS), and traffic management as infrastructure rather than application code. Implemented via sidecar proxies (Envoy).

## Managed Kubernetes
| Provider | Service |
|----------|---------|
| AWS | EKS (Elastic Kubernetes Service) |
| GCP | GKE (Google Kubernetes Engine) |
| Azure | AKS (Azure Kubernetes Service) |

## Serverless Containers
Run containers without managing servers or clusters:
- AWS Fargate (ECS/EKS)
- GCP Cloud Run
- Azure Container Instances

## Possible Interview Questions
1. "How would you deploy and scale a microservices application?"
2. "Explain the difference between a Deployment and a StatefulSet in Kubernetes."
3. "How does Kubernetes handle service discovery?"
4. "How would you implement a canary deployment?"
5. "What happens when a Kubernetes node goes down?"
6. "Compare containers vs VMs. When would you use each?"
7. "How does the Horizontal Pod Autoscaler work?"
