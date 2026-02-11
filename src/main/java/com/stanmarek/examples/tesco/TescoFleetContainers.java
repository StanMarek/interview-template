package com.stanmarek.examples.tesco;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Determine which container fits an order based on volume,
 * and find the maximum number of orders fitting a given container.
 *
 * Simplification: we treat the fitting problem as pure volume comparison
 * (total product volume <= container volume) rather than 3D bin packing.
 * Full 3D packing is NP-hard and overkill for an interview setting — the problem
 * statement gives dimensions but asks "does it fit?", which volume answers well.
 *
 * Two API methods:
 *
 * 1. findFittingContainer — for a single order, find the smallest container
 *    that has enough volume. "Smallest" avoids wasting space on large containers
 *    when a small one suffices (cost efficiency).
 *
 * 2. maxOrdersFitting — given multiple orders, count how many individually fit
 *    into a specific container (each order shipped separately in its own container).
 *
 * Volume is computed as long to avoid int overflow for large dimensions
 * (e.g. 100 * 200 * 300 = 6,000,000 fits int, but real-world values could exceed it).
 *
 * Time: O(orders * products) for volume calculation + O(containers) for lookup.
 * Space: O(products) for the catalog map.
 */
public class TescoFleetContainers {

    public record Container(int id, int length, int breadth, int height) {
        /** Total internal volume of the container. */
        public long volume() {
            return (long) length * breadth * height;
        }
    }

    public record Product(int productId, int length, int breadth, int height) {
        /** Volume occupied by a single unit of this product. */
        public long volume() {
            return (long) length * breadth * height;
        }
    }

    public record OrderLine(int productId, int quantity) {}

    /**
     * Compute total volume needed for an order by summing (product volume * quantity)
     * for each line. Uses a pre-built catalog map for O(1) product lookups.
     */
    public static long orderVolume(List<OrderLine> order, Map<Integer, Product> catalog) {
        long total = 0;
        for (OrderLine line : order) {
            Product p = catalog.get(line.productId());
            if (p == null) throw new IllegalArgumentException("Unknown product: " + line.productId());
            total += p.volume() * line.quantity();
        }
        return total;
    }

    /**
     * Find the smallest container that fits the given order.
     * Returns the container ID, or empty if no container is large enough.
     *
     * Strategy: filter containers with sufficient volume, then pick the one
     * with the smallest volume (best fit) to minimize wasted space.
     */
    public static Optional<Integer> findFittingContainer(
            List<Container> containers, List<Product> products, List<OrderLine> order) {

        // Build productId -> Product map for fast lookups in volume calculation
        Map<Integer, Product> catalog = products.stream()
                .collect(Collectors.toMap(Product::productId, Function.identity()));

        long requiredVolume = orderVolume(order, catalog);

        return containers.stream()
                .filter(c -> c.volume() >= requiredVolume)    // only containers that fit
                .min(Comparator.comparingLong(Container::volume)) // pick smallest sufficient one
                .map(Container::id);
    }

    /**
     * Count how many of the given orders can individually fit into the specified container.
     * Each order is evaluated independently (one order per container trip).
     */
    public static int maxOrdersFitting(
            List<Container> containers, List<Product> products,
            List<List<OrderLine>> orders, int containerId) {

        Map<Integer, Product> catalog = products.stream()
                .collect(Collectors.toMap(Product::productId, Function.identity()));

        // Look up the target container's volume once
        long containerVolume = containers.stream()
                .filter(c -> c.id() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown container: " + containerId))
                .volume();

        int count = 0;
        for (List<OrderLine> order : orders) {
            if (orderVolume(order, catalog) <= containerVolume) {
                count++;
            }
        }
        return count;
    }
}
