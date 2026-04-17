# Graphs

## Overview

Graphs are the most general-purpose data structure — any problem involving relationships, connectivity, paths, or networks is a graph problem. Many problems that don't look like graph problems (word ladders, matrix traversal, dependency resolution) are actually graph problems in disguise.

## Core Concepts

### Terminology

- **Vertex (Node):** An entity. **Edge:** A connection between two vertices.
- **Directed vs. Undirected:** Edges have direction or not.
- **Weighted vs. Unweighted:** Edges have costs or not.
- **Cycle:** A path that starts and ends at the same vertex.
- **DAG:** Directed Acyclic Graph — has topological ordering.
- **Connected Component:** Maximal set of vertices reachable from each other (undirected).
- **Degree:** Number of edges connected to a vertex. In directed graphs: in-degree + out-degree.
- **Sparse graph:** E ≈ V. **Dense graph:** E ≈ V².

### Representations

```java
// 1. Adjacency List (most common in interviews)
Map<Integer, List<Integer>> graph = new HashMap<>();
graph.computeIfAbsent(u, k -> new ArrayList<>()).add(v);
graph.computeIfAbsent(v, k -> new ArrayList<>()).add(u); // undirected

// Or with arrays (when nodes are 0 to n-1)
List<List<Integer>> graph = new ArrayList<>();
for (int i = 0; i < n; i++) graph.add(new ArrayList<>());
graph.get(u).add(v);

// 2. Adjacency Matrix (good for dense graphs, quick edge lookup)
boolean[][] graph = new boolean[n][n];
graph[u][v] = true;
graph[v][u] = true; // undirected

// 3. Edge List (for Kruskal's, Bellman-Ford)
int[][] edges = {{u, v, weight}, ...};

// 4. Weighted Adjacency List
Map<Integer, List<int[]>> graph = new HashMap<>();
graph.computeIfAbsent(u, k -> new ArrayList<>()).add(new int[]{v, weight});
```

### Complexity by Representation

| Operation        | Adjacency List | Adjacency Matrix |
|------------------|----------------|------------------|
| Space            | O(V + E)       | O(V²)            |
| Add Edge         | O(1)           | O(1)             |
| Check Edge       | O(degree)      | O(1)             |
| All Neighbors    | O(degree)      | O(V)             |
| BFS/DFS          | O(V + E)       | O(V²)            |

## Essential Algorithms

### 1. BFS (Breadth-First Search)

Explores level by level. Finds **shortest path in unweighted graphs**.

```java
public int bfs(Map<Integer, List<Integer>> graph, int start, int target) {
    Queue<Integer> queue = new LinkedList<>();
    Set<Integer> visited = new HashSet<>();
    queue.offer(start);
    visited.add(start);
    int level = 0;

    while (!queue.isEmpty()) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            int node = queue.poll();
            if (node == target) return level;
            for (int neighbor : graph.getOrDefault(node, List.of())) {
                if (visited.add(neighbor)) {
                    queue.offer(neighbor);
                }
            }
        }
        level++;
    }
    return -1; // not reachable
}
```

### 2. DFS (Depth-First Search)

Explores as deep as possible first. Used for cycle detection, topological sort, connected components.

```java
// Recursive DFS
Set<Integer> visited = new HashSet<>();

public void dfs(Map<Integer, List<Integer>> graph, int node) {
    visited.add(node);
    for (int neighbor : graph.getOrDefault(node, List.of())) {
        if (!visited.contains(neighbor)) {
            dfs(graph, neighbor);
        }
    }
}

// Iterative DFS
public void dfsIterative(Map<Integer, List<Integer>> graph, int start) {
    Deque<Integer> stack = new ArrayDeque<>();
    Set<Integer> visited = new HashSet<>();
    stack.push(start);

    while (!stack.isEmpty()) {
        int node = stack.pop();
        if (!visited.add(node)) continue;
        for (int neighbor : graph.getOrDefault(node, List.of())) {
            if (!visited.contains(neighbor)) {
                stack.push(neighbor);
            }
        }
    }
}
```

### 3. Topological Sort (DAG only)

Two approaches: Kahn's (BFS) and DFS-based.

```java
// Kahn's Algorithm (BFS) — also detects cycles
public int[] topologicalSort(int numNodes, int[][] edges) {
    List<List<Integer>> graph = new ArrayList<>();
    int[] inDegree = new int[numNodes];
    for (int i = 0; i < numNodes; i++) graph.add(new ArrayList<>());

    for (int[] e : edges) {
        graph.get(e[0]).add(e[1]);
        inDegree[e[1]]++;
    }

    Queue<Integer> queue = new LinkedList<>();
    for (int i = 0; i < numNodes; i++) {
        if (inDegree[i] == 0) queue.offer(i);
    }

    int[] order = new int[numNodes];
    int idx = 0;
    while (!queue.isEmpty()) {
        int node = queue.poll();
        order[idx++] = node;
        for (int neighbor : graph.get(node)) {
            if (--inDegree[neighbor] == 0) {
                queue.offer(neighbor);
            }
        }
    }
    return idx == numNodes ? order : new int[0]; // empty = cycle exists
}
```

### 4. Dijkstra's Algorithm (Shortest Path, Non-Negative Weights)

```java
public int[] dijkstra(Map<Integer, List<int[]>> graph, int src, int n) {
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[src] = 0;

    // {distance, node} — use Integer.compare to avoid overflow on a[0] - b[0]
    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
    pq.offer(new int[]{0, src});

    while (!pq.isEmpty()) {
        int[] curr = pq.poll();
        int d = curr[0], u = curr[1];
        if (d > dist[u]) continue; // skip outdated entries

        for (int[] edge : graph.getOrDefault(u, List.of())) {
            int v = edge[0], w = edge[1];
            if (dist[u] + w < dist[v]) {
                dist[v] = dist[u] + w;
                pq.offer(new int[]{dist[v], v});
            }
        }
    }
    return dist;
}
// Time: O((V + E) log V)
```

### 5. Bellman-Ford (Handles Negative Weights)

```java
public int[] bellmanFord(int[][] edges, int n, int src) {
    int[] dist = new int[n];
    Arrays.fill(dist, Integer.MAX_VALUE);
    dist[src] = 0;

    for (int i = 0; i < n - 1; i++) { // relax n-1 times
        for (int[] e : edges) {
            if (dist[e[0]] != Integer.MAX_VALUE && dist[e[0]] + e[2] < dist[e[1]]) {
                dist[e[1]] = dist[e[0]] + e[2];
            }
        }
    }
    // One more pass to detect negative cycles
    for (int[] e : edges) {
        if (dist[e[0]] != Integer.MAX_VALUE && dist[e[0]] + e[2] < dist[e[1]]) {
            return null; // negative cycle
        }
    }
    return dist;
}
// Time: O(V * E)
```

### 6. Union-Find (Disjoint Set Union)

```java
class UnionFind {
    int[] parent, rank;
    int components;

    UnionFind(int n) {
        parent = new int[n];
        rank = new int[n];
        components = n;
        for (int i = 0; i < n; i++) parent[i] = i;
    }

    int find(int x) {
        if (parent[x] != x) parent[x] = find(parent[x]); // path compression
        return parent[x];
    }

    boolean union(int x, int y) {
        int px = find(x), py = find(y);
        if (px == py) return false;
        if (rank[px] < rank[py]) { int tmp = px; px = py; py = tmp; }
        parent[py] = px;
        if (rank[px] == rank[py]) rank[px]++;
        components--;
        return true;
    }

    boolean connected(int x, int y) { return find(x) == find(y); }
}
// Path compression + union by rank → O(α(n)) ≈ O(1) amortized (inverse Ackermann).
// Path compression alone: O(log n) amortized. Union by rank alone: O(log n) worst-case per op.
// Both together: O(α(n)) amortized (inverse Ackermann — effectively constant).
// Union by size works equally well — replace `rank` with subtree size and merge smaller under larger.
```

### 7. MST — Kruskal vs. Prim

Both build a **minimum spanning tree** on an undirected weighted connected graph.

```java
// Kruskal — sort edges, add if they don't form a cycle (Union-Find). O(E log E).
// Best for sparse graphs / when edges are already sorted.
public int kruskal(int n, int[][] edges) { // edges: {u, v, w}
    Arrays.sort(edges, (a, b) -> Integer.compare(a[2], b[2]));
    UnionFind uf = new UnionFind(n);
    int cost = 0, used = 0;
    for (int[] e : edges) {
        if (uf.union(e[0], e[1])) { cost += e[2]; if (++used == n - 1) break; }
    }
    return used == n - 1 ? cost : -1; // -1 = disconnected
}

// Prim — grow tree from a start vertex with a min-heap. O(E log V).
// Best for dense graphs. Same shape as Dijkstra but relaxes on edge weight, not path sum.
```

### 8. Grid as Graph (Matrix BFS/DFS)

```java
// BFS on a grid — shortest path in a maze
int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};

public int shortestPath(int[][] grid) {
    int m = grid.length, n = grid[0].length;
    Queue<int[]> queue = new LinkedList<>();
    boolean[][] visited = new boolean[m][n];
    queue.offer(new int[]{0, 0});
    visited[0][0] = true;
    int steps = 0;

    while (!queue.isEmpty()) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            int[] cell = queue.poll();
            if (cell[0] == m - 1 && cell[1] == n - 1) return steps;
            for (int[] d : dirs) {
                int r = cell[0] + d[0], c = cell[1] + d[1];
                if (r >= 0 && r < m && c >= 0 && c < n
                    && !visited[r][c] && grid[r][c] == 0) {
                    visited[r][c] = true;
                    queue.offer(new int[]{r, c});
                }
            }
        }
        steps++;
    }
    return -1;
}
```

### 9. 0-1 BFS (Deque Trick)

When edge weights are only **0 or 1**, use an `ArrayDeque`: push-front on weight-0 edges, push-back on weight-1. Runs in **O(V + E)** — a Dijkstra replacement without the log factor.

```java
Deque<int[]> dq = new ArrayDeque<>();
dq.offerFirst(new int[]{src, 0});
while (!dq.isEmpty()) {
    int[] cur = dq.pollFirst();
    int u = cur[0], d = cur[1];
    if (d > dist[u]) continue;
    for (int[] e : graph.get(u)) { // e = {v, w} with w ∈ {0, 1}
        int nd = d + e[1];
        if (nd < dist[e[0]]) {
            dist[e[0]] = nd;
            if (e[1] == 0) dq.offerFirst(new int[]{e[0], nd});
            else           dq.offerLast(new int[]{e[0], nd});
        }
    }
}
```

### 10. Bidirectional BFS

Search from both source and target, alternating the **smaller frontier** each step. Shrinks branching factor from `b^d` to `2·b^(d/2)` — huge win on Word Ladder-style problems.

### 11. A* Search

Dijkstra + admissible heuristic `h(n)`. Uses priority `f(n) = g(n) + h(n)`. `h` must **never overestimate** the true remaining cost (e.g., Manhattan distance on a grid). Optimal iff heuristic is admissible; consistent heuristic also gives monotone `f`.

Admissibility guarantees optimality for tree search; **consistency** (monotonicity) is required for graph search without re-expansions. Consistency implies admissibility.

### 12. Floyd-Warshall (All-Pairs Shortest Path)

`O(V³)` time, `O(V²)` space. Handles negative edges (no negative cycles). Good for small, dense graphs.

```java
int[][] dist = new int[n][n];
for (int[] row : dist) Arrays.fill(row, INF);
for (int i = 0; i < n; i++) dist[i][i] = 0;
for (int[] e : edges) dist[e[0]][e[1]] = e[2];
for (int k = 0; k < n; k++)           // intermediate
  for (int i = 0; i < n; i++)
    for (int j = 0; j < n; j++)
      if (dist[i][k] != INF && dist[k][j] != INF
          && dist[i][k] + dist[k][j] < dist[i][j])
        dist[i][j] = dist[i][k] + dist[k][j];
// Negative cycle iff any dist[i][i] < 0 after the loop.
```

**Johnson's algorithm** is the sparse alternative: O(V·E + V² log V) (with Fibonacci heap; binary heap gives O(V·E log V)) using Bellman-Ford reweighting + Dijkstra from every node. Better than Floyd-Warshall when E ≪ V².

### 13. Tarjan's SCC & Bridges / Articulation Points

- **Kosaraju:** two DFS passes (original + transpose). Simple to code.
- **Tarjan's SCC:** one DFS using `disc[]`/`low[]` and a stack — O(V + E).
- **Bridges (critical edges):** edge `(u, v)` is a bridge iff `low[v] > disc[u]`. Same DFS skeleton.
- **Articulation points:** vertex `u` is cut iff (a) root of DFS with ≥ 2 children, or (b) non-root with some child `v` where `low[v] ≥ disc[u]`.

All share the "DFS timestamps + low-link" pattern. Classic problem: **Critical Connections in a Network** (LC 1192, bridges).

### 14. Eulerian vs. Hamiltonian

- **Eulerian path/circuit** (visit every **edge** exactly once): solvable in O(V + E) via Hierholzer's algorithm. Undirected: circuit iff all degrees even; path iff exactly 0 or 2 odd-degree vertices. Directed: every vertex has in-deg = out-deg (circuit), or exactly one start (out − in = 1) and one end (in − out = 1).
- **Hamiltonian path/cycle** (visit every **vertex** exactly once): **NP-hard**. Use bitmask DP (Held-Karp, `O(2^N · N²)`) for N ≤ ~20.

## Library Shortcuts (JGraphT, Guava)

Interview problems usually require hand-rolled code, but you may be asked "how would you do this at scale?"

```java
// JGraphT — rich algorithm library
Graph<Integer, DefaultWeightedEdge> g = new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);
new DijkstraShortestPath<>(g).getPath(src, dst);
new BellmanFordShortestPath<>(g).getPath(src, dst);
new FloydWarshallShortestPaths<>(g);              // all-pairs
new JohnsonShortestPaths<>(g);                    // all-pairs, sparse
new AStarShortestPath<>(g, heuristic).getPath(s, t);
new KosarajuStrongConnectivityInspector<>(g).getStronglyConnectedComponents();
new GabowStrongConnectivityInspector<>(g);        // Tarjan-family, iterative
new BiconnectivityInspector<>(g).getBridges();    // + getCutpoints()
new KruskalMinimumSpanningTree<>(g); new PrimMinimumSpanningTree<>(g);
new TopologicalOrderIterator<>(g);                // Kahn-based
new CycleDetector<>(g).detectCycles();

// Guava — lightweight graph modeling (no algorithms beyond traversal)
MutableGraph<String> g2 = GraphBuilder.directed().build();
MutableValueGraph<String, Integer> w = ValueGraphBuilder.directed().build();
MutableNetwork<String, Edge> n = NetworkBuilder.directed().allowsParallelEdges(true).build();
Traverser.forGraph(g2).breadthFirst(start);       // BFS iterable
Graphs.transitiveClosure(g2); Graphs.transpose(g2);
```

## Common Interview Problems

### Easy
- Number of Islands (DFS/BFS on grid), Flood Fill
- Find if Path Exists in Graph

### Medium
- Clone Graph, Course Schedule (I, II — topological sort)
- Number of Connected Components, Pacific Atlantic Water Flow
- Rotting Oranges (multi-source BFS), Word Ladder
- Network Delay Time (Dijkstra), Cheapest Flights Within K Stops
- Redundant Connection (Union-Find), Graph Valid Tree
- Surrounded Regions, 01 Matrix

### Hard
- Word Ladder II, Alien Dictionary
- Shortest Path in a Grid with Obstacles Elimination (0-1 BFS or state-augmented BFS)
- Critical Connections in a Network (Tarjan's bridges)
- Reconstruct Itinerary (Eulerian path — Hierholzer's)
- Swim in Rising Water (Dijkstra / binary search + BFS)
- Minimum Cost to Make at Least One Valid Path (0-1 BFS)

## Tips and Pitfalls

- **BFS = shortest path (unweighted), DFS = explore all paths / detect cycles.** Don't mix them up.
- **Mark visited WHEN ADDING to queue**, not when popping. Otherwise you'll add duplicates and waste time/memory.
- **Cycle detection:** In undirected graphs, track parent. In directed graphs, use 3 colors (white/gray/black) or in-degree approach.
- **Grid problems are graph problems.** The grid is an implicit adjacency list. Directions array `{{0,1},{0,-1},{1,0},{-1,0}}` is your friend.
- **Topological sort only works on DAGs.** If Kahn's doesn't process all nodes, there's a cycle.
- **Union-Find vs. DFS for connectivity:** Union-Find is better for dynamic connectivity (edges added over time). DFS/BFS for static graphs.
- **Dijkstra doesn't work with negative weights.** Use Bellman-Ford instead.
- **Multi-source BFS:** Add all sources to the queue initially (e.g., Rotting Oranges, 01 Matrix).
- **Bidirectional BFS** can drastically reduce search space for shortest-path problems.
- **0-1 BFS beats Dijkstra** when weights are only 0/1 — O(V+E) with a deque.
- **Dijkstra priority comparator:** use `Integer.compare(a, b)`, not `a - b` (overflow on large weights).
- **Algorithm picker:** unweighted → BFS; non-neg weights → Dijkstra; any weights → Bellman-Ford; all-pairs → Floyd-Warshall (dense) or Johnson's (sparse); grid with 0/1 moves → 0-1 BFS.
- **Hamiltonian path is NP-hard**, Eulerian is polynomial — don't confuse them. If the problem says "visit every node exactly once" and N ≤ 20, think bitmask DP.
- **Tarjan pattern** (disc/low arrays, DFS stack) solves SCC, bridges, and articulation points with the same skeleton.
