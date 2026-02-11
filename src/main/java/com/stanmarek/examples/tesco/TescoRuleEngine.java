package com.stanmarek.examples.tesco;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restriction rule engine for shopping cart validation.
 * Checks order items against business restriction rules and returns MET or BREACHED.
 *
 * Design choices:
 *
 * - Sealed interface + records for rules: gives exhaustive pattern matching in switch,
 *   so the compiler enforces that every rule type is handled. Adding a new rule type
 *   causes a compile error until its case is added — no silent misses.
 *
 * - Category quantities are pre-aggregated into a map before rule evaluation,
 *   so category-based rules run in O(1) per rule instead of scanning the cart each time.
 *
 * - Category matching is case-insensitive (toLowerCase) because the example data
 *   mixes "Paracetamol" and "paracetamol" — real-world product data is messy.
 *
 * Rule semantics:
 * - BulkBuyLimit: no single line item can exceed maxQuantity.
 *   ("Cannot buy more than 10 Quantity of any products" = per-item cap.)
 * - BulkBuyLimitCategory: total quantity across all items in a category cannot exceed maxQuantity.
 *   ("Cannot buy more than 5 Quantity of paracetamol products" = category-wide cap.)
 *
 * Time: O(items + rules).  Space: O(categories) for the aggregation map.
 */
public class TescoRuleEngine {

    public record OrderItem(int productId, String category, int quantity) {}

    public enum Status { MET, BREACHED }

    public sealed interface Rule permits BulkBuyLimit, BulkBuyLimitCategory {}

    /** No single product line can exceed this quantity. */
    public record BulkBuyLimit(int maxQuantity) implements Rule {}

    /** Total quantity of all products in a given category cannot exceed this limit. */
    public record BulkBuyLimitCategory(String category, int maxQuantity) implements Rule {}

    public static Status checkRules(List<OrderItem> cart, List<Rule> rules) {
        // Pre-aggregate quantities per category for efficient category-based rule checks.
        // This avoids re-scanning the cart for every BulkBuyLimitCategory rule.
        Map<String, Integer> categoryQuantities = new HashMap<>();
        for (OrderItem item : cart) {
            categoryQuantities.merge(
                    item.category().toLowerCase(),
                    item.quantity(),
                    Integer::sum
            );
        }

        // Evaluate each rule — short-circuit on first breach (fail-fast)
        for (Rule rule : rules) {
            switch (rule) {
                case BulkBuyLimit bulk -> {
                    // Check every line item individually: "cannot buy more than X of any product"
                    for (OrderItem item : cart) {
                        if (item.quantity() > bulk.maxQuantity()) return Status.BREACHED;
                    }
                }
                case BulkBuyLimitCategory catLimit -> {
                    // Check aggregated category total: "cannot buy more than X of <category>"
                    int qty = categoryQuantities.getOrDefault(catLimit.category().toLowerCase(), 0);
                    if (qty > catLimit.maxQuantity()) return Status.BREACHED;
                }
            }
        }
        return Status.MET;
    }
}
