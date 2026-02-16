package com.stanmarek.tesco;


import lombok.With;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TescoSolution {

    @With
    public record CartItem(
            String name,
            String category,
            int count,
            BigDecimal price,
            BigDecimal discountedPrice
    ) {

    }

    public sealed interface Rule permits MostExpensiveItemPercentageDiscount, SetAmountVoucherPerCategory {
    }

    public record MostExpensiveItemPercentageDiscount(BigDecimal percentage) implements Rule {
        public CartItem applyDiscount(CartItem cartItem) {
            var basePrice = cartItem.price();
            var fraction = BigDecimal.ONE.subtract(percentage.divide(BigDecimal.valueOf(100)));
            var discountedPrice = basePrice.multiply(fraction).setScale(2, RoundingMode.HALF_UP);

            return cartItem.withDiscountedPrice(discountedPrice);
        }
    }

    public record SetAmountVoucherPerCategory(String category, BigDecimal maxDiscount) implements Rule {
        public List<CartItem> applyDiscount(List<CartItem> cartItemsByCategory) {
            if (!isApplicable(cartItemsByCategory)) {
                return cartItemsByCategory;
            }

            List<CartItem> discountedItems = new ArrayList<>(cartItemsByCategory);
            var remainingAmountToDiscount = maxDiscount;
            for (CartItem item : cartItemsByCategory) {
                if (remainingAmountToDiscount.compareTo(BigDecimal.ZERO) == 0) {
                    break;
                }

                Pair<BigDecimal, CartItem> appliedDiscount = applyDiscount(item, remainingAmountToDiscount);
                remainingAmountToDiscount = appliedDiscount.getLeft();
                discountedItems.remove(item);
                discountedItems.add(appliedDiscount.getRight());
            }

            return discountedItems;
        }

        private Pair<BigDecimal, CartItem> applyDiscount(CartItem cartItem, BigDecimal remainingAmount) {
            if (cartItem.price().compareTo(remainingAmount) >= 0) {
                var discountedPrice = cartItem.price().subtract(remainingAmount);
                CartItem discountedItem = cartItem.withDiscountedPrice(discountedPrice);
                var remainingDiscount = BigDecimal.ZERO;
                return Pair.of(remainingDiscount, discountedItem);
            }

            var maxDiscountToBeApplied = cartItem.price();
            var discountedPrice = cartItem.price().subtract(maxDiscountToBeApplied);
            CartItem discountedItem = cartItem.withDiscountedPrice(discountedPrice);
            var remainingDiscount = remainingAmount.subtract(maxDiscountToBeApplied);

            return Pair.of(remainingDiscount, discountedItem);
        }

        private boolean isApplicable(List<CartItem> cartItemsByCategory) {
            return !cartItemsByCategory.isEmpty();
        }

    }

    public List<CartItem> getItemsByCategory(String category, List<CartItem> allItems) {
        return Optional.ofNullable(groupItemsByCategory(allItems)
                        .get(category))
                .orElse(Collections.emptyList());
    }

    public List<CartItem> calculateCartDiscounts(List<CartItem> cartItems, List<Rule> applicableRules) {
        // test for len = 0 missing
        if (cartItems.isEmpty()) {
            return Collections.emptyList();
        }
        if (applicableRules.isEmpty()) {
            return Collections.unmodifiableList(cartItems);
        }

        List<CartItem> newCartItems = new ArrayList<>(cartItems);

        for (Rule applicableRule : applicableRules) {
            switch (applicableRule) {
                case MostExpensiveItemPercentageDiscount mostExpensiveItemPercentageDiscount -> {
                    CartItem mostExpensiveItem = findMostExpensiveItem(newCartItems).orElseThrow(); // impossible scenario
                    CartItem discountedMostExpensiveItem = mostExpensiveItemPercentageDiscount.applyDiscount(mostExpensiveItem);

                    newCartItems.remove(mostExpensiveItem);
                    newCartItems.add(discountedMostExpensiveItem);
                }
                case SetAmountVoucherPerCategory voucherPerCategory -> {
                    List<CartItem> applicableItems = getItemsByCategory(voucherPerCategory.category, newCartItems);
                    if (applicableItems.isEmpty()) continue;

                    newCartItems.removeAll(applicableItems);
                    newCartItems.addAll(voucherPerCategory.applyDiscount(applicableItems));
                }
            }
        }

        return newCartItems;
    }

    public Optional<CartItem> findMostExpensiveItem(List<CartItem> allItems) {
        return allItems.stream().max(Comparator.comparing(cartItem -> cartItem.price));
    }

    private Map<String, List<CartItem>> groupItemsByCategory(List<CartItem> cartItems) {
        Map<String, List<CartItem>> itemsGrouped = new HashMap<>();
        for (CartItem item : cartItems) {
            var grouping = itemsGrouped.getOrDefault(item.category, new ArrayList<>());
            grouping.add(item);
            itemsGrouped.put(item.category, grouping);
        }

        return itemsGrouped;
    }
}
