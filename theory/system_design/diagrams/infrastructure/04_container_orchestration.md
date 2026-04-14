# Container Orchestration Control Plane -- Architecture Design

## Requirements
### Functional
- Accept declarative workload specifications (desired state) and reconcile to actual state
- Schedule containers onto worker nodes based on resource requests, affinity, and constraints
- Self-heal: restart failed containers, reschedule from failed nodes, replace unresponsive instances
- Rolling updates and rollbacks for workload deployments
- Service discovery and internal DNS for container-to-container communication
- Horizontal and vertical pod autoscaling based on metrics
- Admission control: validate and mutate workload specs before persisting

### Non-Functional
- Control plane available 99.99% -- a control plane outage must not affect running workloads
- Schedule 5000+ pods per minute during burst (large deploys, node failures)
- Cluster size: 100-5000 worker nodes
- API latency: < 100ms for reads, < 500ms for writes
- etcd write throughput: 10K writes/sec sustained

## Scale Estimates
- 5000 worker nodes, 100K running pods
- 200 namespaces, 2000 services
- API server: 50K requests/sec (mostly watches)
- etcd: 100K keys, 2GB total data, 10K writes/sec
- Scheduling decisions: 100-500 pods/sec during normal operation, 5K/sec during recovery

## Architecture Decisions

### Declarative Desired-State Model with Reconciliation Loops
The user declares "I want 3 replicas of service X" and the system continuously reconciles actual state to match. This is fundamentally different from imperative "start a container on node Y." The reconciliation model is self-healing: if a node dies, the controller detects the discrepancy and schedules replacement pods. The trade-off: reconciliation loops add latency (seconds) compared to imperative commands, and the eventual consistency model means brief windows where actual state differs from desired state.

### Single Source of Truth (etcd)
All cluster state lives in etcd, accessed exclusively through the API server. No component stores authoritative state locally. This makes the system recoverable (restore etcd backup = restore entire cluster state) but makes etcd the critical bottleneck. If etcd is slow, the entire control plane is slow.

### Scheduler as a Separate Component
The scheduler is decoupled from the API server. It watches for unscheduled pods, scores nodes using a plugin-based framework (resource fit, affinity, topology spread), and binds pods to nodes. This separation means you can run multiple schedulers or swap scheduling algorithms without touching the API server. Trade-off: the scheduler sees a slightly stale view of node resources, which can lead to overcommitment under rapid scheduling.

### Controller Manager as a Reconciliation Hub
Each controller (ReplicaSet, Deployment, StatefulSet, Job) runs as an independent reconciliation loop within the controller manager. Controllers are level-triggered (they look at current state, not events) so they self-correct after any transient failure. The controller manager itself is active-passive for leader election.

## Component Breakdown

- **API Server**: The gateway to all cluster operations. Authenticates, authorizes (RBAC), validates, and persists to etcd. Implements optimistic concurrency via resource versions. Supports efficient watch streams for change notification.
- **etcd Cluster (3-5 nodes)**: Distributed key-value store using Raft consensus. Stores all cluster state: pods, services, configmaps, secrets, node status. Leader handles writes, all nodes handle linearizable reads. Backup is critical -- loss of etcd = loss of cluster state.
- **Scheduler**: Watches for pods with no assigned node. Filters nodes (resource fit, taints, node selectors), scores remaining nodes (bin-packing, spreading, affinity), and binds the pod to the highest-scoring node. Scheduling is the most CPU-intensive control plane operation.
- **Controller Manager**: Runs ~30 built-in controllers. Key ones: ReplicaSet controller (ensure N replicas running), Deployment controller (rolling updates), Node controller (detect node failures, taint unresponsive nodes), Service controller (manage cloud LB resources).
- **Admission Controllers**: Chain of webhooks that intercept API requests. Validating admission (reject bad configs) and mutating admission (inject sidecar containers, add labels). Critical for policy enforcement (security contexts, resource limits).
- **Cluster Autoscaler**: Watches for unschedulable pods (no node has capacity) and provisions new nodes from the cloud provider. Also scales down underutilized nodes. Interacts with cloud provider APIs.
- **HPA / VPA**: Horizontal Pod Autoscaler adjusts replica count based on CPU/memory/custom metrics. Vertical Pod Autoscaler adjusts resource requests. HPA is widely used; VPA is less mature and requires pod restarts.
- **Kubelet**: Agent on each worker node. Receives pod specs from the API server, manages container lifecycle through containerd, reports node status and pod status back. Also handles liveness/readiness probes.
- **kube-proxy**: Manages network rules (iptables or IPVS) for service ClusterIPs. Routes traffic from service VIPs to backing pod IPs. As of K8s 1.32/1.33 (current at time of writing), the standard production pattern is replacing kube-proxy with eBPF data planes (Cilium with kube-proxy-replacement, Calico eBPF) for higher throughput, lower latency, and richer observability (Hubble flow logs). The Gateway API (GA in 1.31) also supersedes Ingress for advanced L7 routing, traffic splitting, and mesh integration.

## Operational Concerns
- **Deploying control plane upgrades safely**: Upgrade one component at a time. etcd first (backward compatible), then API server, then controller manager and scheduler. API server supports running N-1 and N versions simultaneously. Never skip versions.
- **Blast radius of a bad admission webhook**: A broken mutating webhook can block ALL pod creation cluster-wide. Mitigation: use failurePolicy=Ignore for non-critical webhooks, set timeouts (5s), and exclude system namespaces.
- **Rollback**: For workloads, Deployment objects maintain revision history and support one-command rollback. For control plane, revert binary versions. For etcd, restore from backup (nuclear option, loses state since backup).
- **etcd maintenance**: Regular compaction (prevent unbounded history growth), defragmentation (reclaim disk space), and backups (every 5 minutes to object storage). Monitor etcd latency -- when p99 write latency exceeds 50ms, the cluster feels slow.

## Failure Modes
- **etcd leader failure**: Raft elects a new leader in 1-5 seconds. Writes are blocked during election but running workloads are unaffected. Watch streams may disconnect and reconnect.
- **API server failure**: If running multiple API servers behind a load balancer, traffic shifts to healthy instances. If all API servers are down, no new changes can be made but existing workloads continue running. Kubelets cache their pod specs.
- **Scheduler failure**: Pods remain in "Pending" state. No new scheduling happens. Running pods are unaffected. The backlog is processed when the scheduler recovers.
- **Controller manager failure**: Reconciliation stops. If a pod dies, the ReplicaSet controller is not running to create a replacement. The discrepancy accumulates until the controller manager recovers, at which point it catches up.
- **Worker node failure**: Node controller detects the node as unresponsive after 40s (default). Taints the node, which triggers pod eviction. Pods are rescheduled to healthy nodes. StatefulSet pods wait for the old node to be confirmed dead before rescheduling (to avoid data corruption).

## Key Trade-offs
- **Consistency vs Availability of control plane**: etcd uses Raft (CP). If a majority of etcd nodes fail, the cluster cannot process writes. Running workloads are unaffected, but new deployments, scaling, and self-healing stop. This is acceptable because control plane outages are rare and recoverable.
- **Scheduling speed vs Placement quality**: Fast scheduling (first-fit) gets pods running quickly but may lead to poor bin-packing. Thorough scoring (evaluate all nodes) gives better placement but takes longer. The default scheduler balances this with a percentageOfNodesToScore parameter.
- **Pod density vs Isolation**: Running more pods per node improves utilization but increases blast radius (node failure affects more pods) and noisy-neighbor risk. Resource limits and pod disruption budgets mitigate this.
- **Watch-based architecture vs Polling**: Watches are efficient (push changes) but complex (need reconnection logic, bookmark handling). Polling is simple but wasteful. The entire Kubernetes architecture is built on watches.

## What Fails First
etcd disk I/O. etcd writes every mutation to disk (WAL + snapshot). When disk latency spikes (noisy neighbor on shared storage, SSD wear), etcd falls behind on Raft heartbeats and the leader steps down. This cascades: API server writes fail, scheduler cannot bind pods, controllers cannot reconcile. Dedicated SSDs with guaranteed IOPS for etcd nodes is non-negotiable for production clusters.

## v1 vs v2
### v1 (Minimum Viable Orchestrator)
- 3-node etcd cluster on dedicated machines
- Single API server (no HA)
- Default scheduler with basic resource-fit scoring
- Built-in controllers only (no custom controllers)
- Manual node provisioning
- Basic RBAC
- Single availability zone

### v2 (Production Grade)
- 5-node etcd with cross-AZ placement and automated backup
- 3+ API server replicas behind internal load balancer
- Custom scheduler plugins for topology-aware scheduling
- Cluster autoscaler with multiple node pools (spot, on-demand, GPU)
- HPA with custom metrics (Prometheus adapter)
- Pod Disruption Budgets for safe maintenance
- Admission webhooks for security policy (OPA Gatekeeper or Kyverno; with K8s 1.30+, Validating Admission Policies / CEL provide an in-tree alternative for simple rules without a webhook hop)
- Multi-AZ topology spread constraints
- etcd encryption at rest for secrets
- Audit logging for all API requests
- Priority classes and preemption for critical workloads
