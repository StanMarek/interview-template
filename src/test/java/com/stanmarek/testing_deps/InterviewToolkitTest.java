package com.stanmarek.testing_deps;

import com.google.common.collect.BiMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import com.google.common.graph.MutableGraph;
import io.vavr.Tuple;
import io.vavr.control.Either;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.api.map.primitive.IntIntMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class InterviewToolkitTest {

    // ── Lombok ──

    @Nested
    class LombokTests {
        @Test
        void pointValueClass() {
            var p1 = new InterviewToolkit.Point(1, 2);
            var p2 = new InterviewToolkit.Point(1, 2);
            var p3 = new InterviewToolkit.Point(3, 4);

            assertThat(p1).isEqualTo(p2);
            assertThat(p1).isNotEqualTo(p3);
            assertThat(p1.getX()).isEqualTo(1);
            assertThat(p1.getY()).isEqualTo(2);
            assertThat(p1.toString()).contains("1", "2");
        }
    }

    // ── Guava ──

    @Nested
    class GuavaTests {
        @Test
        void multisetFrequencyCount() {
            Multiset<String> freq = InterviewToolkit.frequencyCount(
                    List.of("a", "b", "a", "c", "a", "b")
            );

            assertThat(freq.count("a")).isEqualTo(3);
            assertThat(freq.count("b")).isEqualTo(2);
            assertThat(freq.count("c")).isEqualTo(1);
            assertThat(freq.count("z")).isEqualTo(0);
        }

        @Test
        void multimapAdjacencyList() {
            Multimap<String, String> adj = InterviewToolkit.buildAdjacencyList(List.of(
                    Pair.of("A", "B"),
                    Pair.of("A", "C"),
                    Pair.of("B", "C")
            ));

            assertThat(adj.get("A")).containsExactlyInAnyOrder("B", "C");
            assertThat(adj.get("B")).containsExactly("C");
            assertThat(adj.get("D")).isEmpty();
        }

        @Test
        void biMapBidirectionalLookup() {
            BiMap<String, Integer> biMap = InterviewToolkit.buildBiMap(
                    Map.of("one", 1, "two", 2, "three", 3)
            );

            assertThat(biMap.get("two")).isEqualTo(2);
            assertThat(biMap.inverse().get(3)).isEqualTo("three");
        }

        @Test
        void tableMultiplication() {
            Table<Integer, Integer, Integer> table = InterviewToolkit.multiplicationTable(5);

            assertThat(table.get(3, 4)).isEqualTo(12);
            assertThat(table.get(5, 5)).isEqualTo(25);
            assertThat(table.get(1, 1)).isEqualTo(1);
            assertThat(table.rowKeySet()).hasSize(5);
        }

        @Test
        void graphApiUndirected() {
            MutableGraph<String> graph = InterviewToolkit.buildUndirectedGraph(List.of(
                    Pair.of("A", "B"),
                    Pair.of("B", "C")
            ));

            assertThat(graph.adjacentNodes("B")).containsExactlyInAnyOrder("A", "C");
            assertThat(graph.hasEdgeConnecting("A", "B")).isTrue();
            assertThat(graph.hasEdgeConnecting("A", "C")).isFalse();
            assertThat(graph.degree("B")).isEqualTo(2);
        }
    }

    // ── Commons Lang ──

    @Nested
    class CommonsLangTests {
        @Test
        void minMaxPair() {
            Pair<Integer, Integer> result = InterviewToolkit.minMax(new int[]{3, 1, 4, 1, 5, 9, 2, 6});

            assertThat(result.getLeft()).isEqualTo(1);
            assertThat(result.getRight()).isEqualTo(9);
        }

        @Test
        void minMaxSingleElement() {
            Pair<Integer, Integer> result = InterviewToolkit.minMax(new int[]{42});

            assertThat(result.getLeft()).isEqualTo(42);
            assertThat(result.getRight()).isEqualTo(42);
        }
    }

    // ── Commons Collections ──

    @Nested
    class CommonsCollectionsTests {
        @Test
        void trieAutocomplete() {
            List<String> dict = List.of("apple", "application", "apply", "banana", "band", "apex");
            List<String> results = InterviewToolkit.autocomplete(dict, "app");

            assertThat(results).containsExactlyInAnyOrder("apple", "application", "apply");
        }

        @Test
        void trieAutocompletNoMatch() {
            List<String> dict = List.of("apple", "banana");
            List<String> results = InterviewToolkit.autocomplete(dict, "xyz");

            assertThat(results).isEmpty();
        }

        @Test
        void bagFrequencyCount() {
            Map<String, Integer> counts = InterviewToolkit.bagCount(
                    List.of("x", "y", "x", "z", "x", "y")
            );

            assertThat(counts).containsEntry("x", 3);
            assertThat(counts).containsEntry("y", 2);
            assertThat(counts).containsEntry("z", 1);
        }
    }

    // ── Commons Math ──

    @Nested
    class CommonsMathTests {
        @ParameterizedTest
        @CsvSource({
                "12, 8,  4",
                "17, 13, 1",
                "100, 75, 25",
                "0, 5,  5",
        })
        void gcd(long a, long b, long expected) {
            assertThat(InterviewToolkit.gcd(a, b)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "4, 6,  12",
                "3, 5,  15",
                "7, 7,  7",
        })
        void lcm(long a, long b, long expected) {
            assertThat(InterviewToolkit.lcm(a, b)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "5, 2, 10",
                "10, 3, 120",
                "6, 0, 1",
                "6, 6, 1",
        })
        void binomialCoefficient(int n, int k, long expected) {
            assertThat(InterviewToolkit.binomial(n, k)).isEqualTo(expected);
        }
    }

    // ── Eclipse Collections ──

    @Nested
    class EclipseCollectionsTests {
        @Test
        void primitiveIntList() {
            IntList list = InterviewToolkit.toPrimitiveList(5, 3, 1, 4, 2);

            assertThat(list.size()).isEqualTo(5);
            assertThat(list.get(0)).isEqualTo(5);
            assertThat(list.min()).isEqualTo(1);
            assertThat(list.max()).isEqualTo(5);
            assertThat(list.sum()).isEqualTo(15);
            assertThat(list.average()).isEqualTo(3.0);
            assertThat(list.median()).isEqualTo(3.0);
        }

        @Test
        void primitiveFrequencyMap() {
            IntIntMap freq = InterviewToolkit.buildFrequencyMap(new int[]{1, 2, 2, 3, 3, 3});

            assertThat(freq.get(1)).isEqualTo(1);
            assertThat(freq.get(2)).isEqualTo(2);
            assertThat(freq.get(3)).isEqualTo(3);
            assertThat(freq.get(99)).isEqualTo(0); // missing key returns 0
        }
    }

    // ── JGraphT ──

    @Nested
    class JGraphTTests {
        @Test
        void dijkstraShortestPath() {
            //   A --1--> B --2--> D
            //   |                 ^
            //   +---10----------->+
            var vertices = List.of("A", "B", "C", "D");
            var edges = List.of(
                    new InterviewToolkit.WeightedEdge("A", "B", 1),
                    new InterviewToolkit.WeightedEdge("B", "C", 2),
                    new InterviewToolkit.WeightedEdge("C", "D", 3),
                    new InterviewToolkit.WeightedEdge("A", "D", 10)
            );

            assertThat(InterviewToolkit.shortestPathWeight(vertices, edges, "A", "D"))
                    .isEqualTo(6.0); // A->B->C->D = 1+2+3
        }

        @Test
        void dijkstraNoPath() {
            var vertices = List.of("A", "B");
            List<InterviewToolkit.WeightedEdge> edges = List.of();

            assertThat(InterviewToolkit.shortestPathWeight(vertices, edges, "A", "B"))
                    .isEqualTo(Double.POSITIVE_INFINITY);
        }
    }

    // ── Vavr ──

    @Nested
    class VavrTests {
        @ParameterizedTest
        @CsvSource({
                "17, 5, 3, 2",
                "10, 3, 3, 1",
                "100, 7, 14, 2",
        })
        void divideWithRemainder(int a, int b, int expectedQuotient, int expectedRemainder) {
            var result = InterviewToolkit.divideWithRemainder(a, b);

            assertThat(result).isEqualTo(Tuple.of(expectedQuotient, expectedRemainder));
        }

        @Test
        void safeDivideSuccess() {
            Either<String, Integer> result = InterviewToolkit.safeDivide(10, 3);

            assertThat(result.isRight()).isTrue();
            assertThat(result.get()).isEqualTo(3);
        }

        @Test
        void safeDivideByZero() {
            Either<String, Integer> result = InterviewToolkit.safeDivide(10, 0);

            assertThat(result.isLeft()).isTrue();
            assertThat(result.getLeft()).isEqualTo("Division by zero");
        }
    }
}
