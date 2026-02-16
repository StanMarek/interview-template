package com.stanmarek.tesco;


import lombok.With;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        List<CartItem> apply(List<CartItem> cartItems);
    }

    public record MostExpensiveItemPercentageDiscount(BigDecimal percentage) implements Rule {
        @Override
        public List<CartItem> apply(List<CartItem> cartItems) {
            if (cartItems.isEmpty()) {
                return cartItems;
            }
            CartItem mostExpensive = cartItems.stream()
                    .max(Comparator.comparing(CartItem::price))
                    .orElseThrow(() -> new IllegalStateException("No items in cart to discount"));

            List<CartItem> result = new ArrayList<>(cartItems.size());
            boolean applied = false;
            for (CartItem item : cartItems) {
                if (!applied && item == mostExpensive) {
                    result.add(applyDiscount(item));
                    applied = true;
                } else {
                    result.add(item);
                }
            }
            return result;
        }

        private CartItem applyDiscount(CartItem cartItem) {
            var basePrice = cartItem.price();
            var priceMultiplier = BigDecimal.ONE.subtract(percentage.divide(BigDecimal.valueOf(100)));
            var discountedPrice = basePrice.multiply(priceMultiplier).setScale(2, RoundingMode.HALF_UP);

            return cartItem.withDiscountedPrice(discountedPrice);
        }
    }

    public record SetAmountVoucherPerCategory(String category, BigDecimal maxDiscount) implements Rule {
        @Override
        public List<CartItem> apply(List<CartItem> cartItems) {
            List<CartItem> result = new ArrayList<>(cartItems.size());
            var remainingAmountToDiscount = maxDiscount;
            for (CartItem item : cartItems) {
                if (!item.category().equals(category) || remainingAmountToDiscount.compareTo(BigDecimal.ZERO) == 0) {
                    result.add(item);
                } else {
                    Pair<BigDecimal, CartItem> discountResult = applyDiscount(item, remainingAmountToDiscount);
                    remainingAmountToDiscount = discountResult.getLeft();
                    result.add(discountResult.getRight());
                }
            }
            return result;
        }

        private Pair<BigDecimal, CartItem> applyDiscount(CartItem cartItem, BigDecimal remainingAmount) {
            if (remainingAmount.compareTo(cartItem.price()) >= 0) {
                // Voucher covers full item price — item becomes free
                return Pair.of(remainingAmount.subtract(cartItem.price()),
                               cartItem.withDiscountedPrice(BigDecimal.ZERO));
            }
            // Partial discount — apply whatever remains
            return Pair.of(BigDecimal.ZERO,
                           cartItem.withDiscountedPrice(cartItem.price().subtract(remainingAmount)));
        }
    }

    public List<CartItem> calculateCartDiscounts(List<CartItem> cartItems, List<Rule> applicableRules) {
        if (cartItems.isEmpty() || applicableRules.isEmpty()) {
            return List.copyOf(cartItems);
        }
        List<CartItem> result = new ArrayList<>(cartItems);
        for (Rule rule : applicableRules) {
            result = rule.apply(result);
        }
        return result;
    }

    public Optional<CartItem> findMostExpensiveItem(List<CartItem> allItems) {
        return allItems.stream().max(Comparator.comparing(cartItem -> cartItem.price));
    }
}
