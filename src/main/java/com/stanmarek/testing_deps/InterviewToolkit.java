package com.stanmarek.testing_deps;

import com.google.common.collect.*;
import com.google.common.graph.*;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.control.Either;
import lombok.Value;
import org.apache.commons.collections4.Bag;
import org.apache.commons.collections4.bag.HashBag;
import org.apache.commons.collections4.trie.PatriciaTrie;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.ArithmeticUtils;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.map.primitive.IntIntMap;
import org.eclipse.collections.impl.factory.primitive.IntIntMaps;
import org.eclipse.collections.impl.factory.primitive.IntLists;
import org.jgrapht.Graph;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import java.util.*;

/**
 * Demonstrates interview-useful features from each library dependency.
 */
public class InterviewToolkit {

    // ── Lombok ──────────────────────────────────────────────

    @Value
    public static class Point {
        int x;
        int y;
    }

    // ── Guava: Multiset (frequency counter, like Python's Counter) ──

    public static <T> Multiset<T> frequencyCount(Iterable<T> items) {
        return HashMultiset.create(items);
    }

    // ── Guava: Multimap (adjacency list style map) ──

    public static Multimap<String, String> buildAdjacencyList(List<Pair<String, String>> edges) {
        ArrayListMultimap<String, String> adj = ArrayListMultimap.create();
        for (Pair<String, String> edge : edges) {
            adj.put(edge.getLeft(), edge.getRight());
        }
        return adj;
    }

    // ── Guava: BiMap (bidirectional mapping) ──

    public static <K, V> BiMap<K, V> buildBiMap(Map<K, V> source) {
        return HashBiMap.create(source);
    }

    // ── Guava: Table (2D map, e.g. for DP tables) ──

    public static Table<Integer, Integer, Integer> multiplicationTable(int n) {
        Table<Integer, Integer, Integer> table = HashBasedTable.create();
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= n; j++) {
                table.put(i, j, i * j);
            }
        }
        return table;
    }

    // ── Guava: Graph API ──

    public static MutableGraph<String> buildUndirectedGraph(List<Pair<String, String>> edges) {
        MutableGraph<String> graph = GraphBuilder.undirected().build();
        for (Pair<String, String> edge : edges) {
            graph.putEdge(edge.getLeft(), edge.getRight());
        }
        return graph;
    }

    // ── Commons Lang: Pair for returning two values ──

    public static Pair<Integer, Integer> minMax(int[] arr) {
        int min = arr[0], max = arr[0];
        for (int v : arr) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return Pair.of(min, max);
    }

    // ── Commons Collections: PatriciaTrie (prefix search) ──

    public static List<String> autocomplete(List<String> dictionary, String prefix) {
        PatriciaTrie<Boolean> trie = new PatriciaTrie<>();
        for (String word : dictionary) {
            trie.put(word, Boolean.TRUE);
        }
        return new ArrayList<>(trie.prefixMap(prefix).keySet());
    }

    // ── Commons Collections: Bag (another frequency counter) ──

    public static <T> Map<T, Integer> bagCount(Collection<T> items) {
        Bag<T> bag = new HashBag<>(items);
        Map<T, Integer> result = new HashMap<>();
        for (T item : bag.uniqueSet()) {
            result.put(item, bag.getCount(item));
        }
        return result;
    }

    // ── Commons Math: GCD, LCM, Binomial Coefficient ──

    public static long gcd(long a, long b) {
        return ArithmeticUtils.gcd(a, b);
    }

    public static long lcm(long a, long b) {
        return ArithmeticUtils.lcm(a, b);
    }

    public static long binomial(int n, int k) {
        return CombinatoricsUtils.binomialCoefficient(n, k);
    }

    // ── Eclipse Collections: Primitive collections (no boxing) ──

    public static IntList toPrimitiveList(int... values) {
        return IntLists.immutable.of(values);
    }

    public static IntIntMap buildFrequencyMap(int[] arr) {
        var map = IntIntMaps.mutable.empty();
        for (int v : arr) {
            map.addToValue(v, 1);
        }
        return map;
    }

    // ── JGraphT: Weighted shortest path ──

    public static double shortestPathWeight(
            List<String> vertices,
            List<WeightedEdge> edges,
            String source,
            String target
    ) {
        Graph<String, DefaultWeightedEdge> graph = new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        vertices.forEach(graph::addVertex);
        for (WeightedEdge e : edges) {
            DefaultWeightedEdge edge = graph.addEdge(e.from, e.to);
            graph.setEdgeWeight(edge, e.weight);
        }
        var path = new DijkstraShortestPath<>(graph).getPath(source, target);
        return path == null ? Double.POSITIVE_INFINITY : path.getWeight();
    }

    public record WeightedEdge(String from, String to, double weight) {}

    // ── Vavr: Tuples and Either ──

    public static Tuple2<Integer, Integer> divideWithRemainder(int a, int b) {
        return Tuple.of(a / b, a % b);
    }

    public static Either<String, Integer> safeDivide(int a, int b) {
        if (b == 0) {
            return Either.left("Division by zero");
        }
        return Either.right(a / b);
    }
}
