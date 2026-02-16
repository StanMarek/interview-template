package com.stanmarek.tesco;

import com.stanmarek.tesco.TescoSolution.CartItem;
import com.stanmarek.tesco.TescoSolution.MostExpensiveItemPercentageDiscount;
import com.stanmarek.tesco.TescoSolution.Rule;
import com.stanmarek.tesco.TescoSolution.SetAmountVoucherPerCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TescoSolutionTest {

    private final TescoSolution solution = new TescoSolution();

    private static CartItem item(String name, String category, int count, String price) {
        return new CartItem(name, category, count, new BigDecimal(price), new BigDecimal(price));
    }

    private static CartItem findByName(List<CartItem> items, String name) {
        return items.stream()
                .filter(i -> i.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item not found: " + name));
    }

    @Nested
    class CalculateCartDiscounts {

        @Test
        void emptyCart_returnsEmptyList() {
            var result = solution.calculateCartDiscounts(Collections.emptyList(), List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void noRules_returnsOriginalItems() {
            var items = List.of(
                    item("A", "cat1", 1, "5.00"),
                    item("B", "cat2", 1, "10.00")
            );
            var result = solution.calculateCartDiscounts(items, List.of());
            assertThat(result).hasSize(2);
        }

        @Test
        void fullProblemScenario() {
            var items = List.of(
                    item("Ham Sandwich", "sandwich", 1, "3"),
                    item("Cheese Sandwich", "sandwich", 2, "3"),
                    item("Orange Juice", "drink", 1, "1.85"),
                    item("Apple Juice", "drink", 2, "5.2")
            );
            List<Rule> rules = List.of(
                    new MostExpensiveItemPercentageDiscount(new BigDecimal("30")),
                    new SetAmountVoucherPerCategory("sandwich", new BigDecimal("5"))
            );

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(result).hasSize(4);
            assertThat(findByName(result, "Ham Sandwich").discountedPrice())
                    .isEqualByComparingTo("0");
            assertThat(findByName(result, "Cheese Sandwich").discountedPrice())
                    .isEqualByComparingTo("1");
            assertThat(findByName(result, "Orange Juice").discountedPrice())
                    .isEqualByComparingTo("1.85");
            assertThat(findByName(result, "Apple Juice").discountedPrice())
                    .isEqualByComparingTo("3.64");
        }

        @Test
        void rulesAppliedAgainstAccumulatedResult() {
            var items = List.of(
                    item("Expensive", "food", 1, "10.00"),
                    item("Cheap", "food", 1, "2.00")
            );
            List<Rule> rules = List.of(
                    new MostExpensiveItemPercentageDiscount(new BigDecimal("50")),
                    new SetAmountVoucherPerCategory("food", new BigDecimal("3"))
            );

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(result).hasSize(2);
            assertThat(findByName(result, "Expensive").discountedPrice())
                    .isEqualByComparingTo("7.00");
            assertThat(findByName(result, "Cheap").discountedPrice())
                    .isEqualByComparingTo("2.00");
        }
    }

    @Nested
    class MostExpensiveItemPercentageDiscountTest {

        @Test
        void appliesPercentageToMostExpensiveItem() {
            var items = List.of(
                    item("A", "cat", 1, "10.00"),
                    item("B", "cat", 1, "20.00")
            );
            List<Rule> rules = List.of(new MostExpensiveItemPercentageDiscount(new BigDecimal("30")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "B").discountedPrice())
                    .isEqualByComparingTo("14.00");
            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("10.00");
        }

        @Test
        void hundredPercentDiscount_makesFree() {
            var items = List.of(item("A", "cat", 1, "15.00"));
            List<Rule> rules = List.of(new MostExpensiveItemPercentageDiscount(new BigDecimal("100")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void zeroPercentDiscount_noChange() {
            var items = List.of(item("A", "cat", 1, "15.00"));
            List<Rule> rules = List.of(new MostExpensiveItemPercentageDiscount(BigDecimal.ZERO));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("15.00");
        }

        @Test
        void roundsToTwoDecimalPlaces() {
            var items = List.of(item("A", "cat", 1, "10.00"));
            List<Rule> rules = List.of(new MostExpensiveItemPercentageDiscount(new BigDecimal("33")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("6.70");
        }

        @Test
        void tiedMostExpensive_discountsOneOfThem() {
            var items = List.of(
                    item("A", "cat", 1, "10.00"),
                    item("B", "cat", 1, "10.00")
            );
            List<Rule> rules = List.of(new MostExpensiveItemPercentageDiscount(new BigDecimal("50")));

            var result = solution.calculateCartDiscounts(items, rules);

            var prices = result.stream().map(CartItem::discountedPrice).toList();
            assertThat(prices).anySatisfy(p -> assertThat(p).isEqualByComparingTo("5.00"));
            assertThat(prices).anySatisfy(p -> assertThat(p).isEqualByComparingTo("10.00"));
        }
    }

    @Nested
    class SetAmountVoucherPerCategoryTest {

        @Test
        void voucherSmallerThanTotal_distributesAcrossItems() {
            var items = List.of(
                    item("A", "food", 1, "3.00"),
                    item("B", "food", 1, "3.00")
            );
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("5")));

            var result = solution.calculateCartDiscounts(items, rules);

            var discountedPrices = result.stream()
                    .map(CartItem::discountedPrice)
                    .sorted()
                    .toList();
            assertThat(discountedPrices).hasSize(2);
            assertThat(discountedPrices.get(0)).isEqualByComparingTo("0");
            assertThat(discountedPrices.get(1)).isEqualByComparingTo("1");
        }

        @Test
        void voucherExactlyEqualsTotal_allItemsFree() {
            var items = List.of(
                    item("A", "food", 1, "3.00"),
                    item("B", "food", 1, "2.00")
            );
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("5")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(result).allSatisfy(item ->
                    assertThat(item.discountedPrice()).isEqualByComparingTo("0"));
        }

        @Test
        void voucherLargerThanTotal_allItemsFree() {
            var items = List.of(
                    item("A", "food", 1, "2.00"),
                    item("B", "food", 1, "1.00")
            );
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("10")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(result).allSatisfy(item ->
                    assertThat(item.discountedPrice()).isEqualByComparingTo("0"));
        }

        @Test
        void categoryNotInCart_noChanges() {
            var items = List.of(item("A", "drink", 1, "5.00"));
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("3")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("5.00");
        }

        @Test
        void onlyAffectsTargetCategory() {
            var items = List.of(
                    item("Sandwich", "food", 1, "3.00"),
                    item("Juice", "drink", 1, "2.00")
            );
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("3")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "Sandwich").discountedPrice())
                    .isEqualByComparingTo("0");
            assertThat(findByName(result, "Juice").discountedPrice())
                    .isEqualByComparingTo("2.00");
        }

        @Test
        void singleItemInCategory_capsDiscountAtItemPrice() {
            var items = List.of(item("A", "food", 1, "2.00"));
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("5")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("0");
        }

        @Test
        void singleItemInCategory_voucherSmallerThanPrice() {
            var items = List.of(item("A", "food", 1, "10.00"));
            List<Rule> rules = List.of(new SetAmountVoucherPerCategory("food", new BigDecimal("3")));

            var result = solution.calculateCartDiscounts(items, rules);

            assertThat(findByName(result, "A").discountedPrice())
                    .isEqualByComparingTo("7.00");
        }
    }

    @Nested
    class FindMostExpensiveItemTest {

        @Test
        void returnsHighestPricedItem() {
            var items = List.of(
                    item("A", "cat", 1, "5.00"),
                    item("B", "cat", 1, "20.00"),
                    item("C", "cat", 1, "10.00")
            );
            assertThat(solution.findMostExpensiveItem(items))
                    .isPresent()
                    .get()
                    .extracting(CartItem::name)
                    .isEqualTo("B");
        }

        @Test
        void emptyList_returnsEmpty() {
            assertThat(solution.findMostExpensiveItem(Collections.emptyList())).isEmpty();
        }

        @Test
        void singleItem_returnsThatItem() {
            var items = List.of(item("A", "cat", 1, "5.00"));
            assertThat(solution.findMostExpensiveItem(items))
                    .isPresent()
                    .get()
                    .extracting(CartItem::name)
                    .isEqualTo("A");
        }
    }

}
