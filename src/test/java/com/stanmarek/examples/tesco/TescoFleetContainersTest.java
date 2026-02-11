package com.stanmarek.examples.tesco;

import com.stanmarek.examples.tesco.TescoFleetContainers.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TescoFleetContainersTest {

    static final List<Container> CONTAINERS = List.of(
            new Container(1, 10, 20, 30),    // volume = 6,000
            new Container(2, 50, 60, 70),    // volume = 210,000
            new Container(3, 100, 200, 300)  // volume = 6,000,000
    );

    static final List<Product> PRODUCTS = List.of(
            new Product(1, 2, 4, 10),  // volume = 80
            new Product(2, 10, 30, 4), // volume = 1,200
            new Product(3, 5, 6, 7)    // volume = 210
    );

    @Test
    void exampleOrder_fitsSmallContainer() {
        // productId=1 qty=3 → 80*3=240, productId=3 qty=7 → 210*7=1470, total=1710
        var order = List.of(new OrderLine(1, 3), new OrderLine(3, 7));
        Optional<Integer> result = TescoFleetContainers.findFittingContainer(CONTAINERS, PRODUCTS, order);
        assertThat(result).isPresent().contains(1); // smallest that fits (6000 >= 1710)
    }

    @Test
    void largeOrder_needsMediumContainer() {
        // productId=2 qty=10 → 1200*10=12000 > 6000 (small), fits medium (210000)
        var order = List.of(new OrderLine(2, 10));
        Optional<Integer> result = TescoFleetContainers.findFittingContainer(CONTAINERS, PRODUCTS, order);
        assertThat(result).isPresent().contains(2);
    }

    @Test
    void hugeOrder_noContainerFits() {
        // productId=2 qty=10000 → 1200*10000=12,000,000 > 6,000,000
        var order = List.of(new OrderLine(2, 10000));
        Optional<Integer> result = TescoFleetContainers.findFittingContainer(CONTAINERS, PRODUCTS, order);
        assertThat(result).isEmpty();
    }

    @Test
    void emptyOrder_fitsSmallest() {
        Optional<Integer> result = TescoFleetContainers.findFittingContainer(CONTAINERS, PRODUCTS, List.of());
        assertThat(result).isPresent().contains(1);
    }

    @Test
    void maxOrdersFitting_smallContainer() {
        var order1 = List.of(new OrderLine(1, 3), new OrderLine(3, 7));  // 1710
        var order2 = List.of(new OrderLine(2, 10));                       // 12000
        var order3 = List.of(new OrderLine(1, 1));                        // 80

        List<List<OrderLine>> orders = List.of(order1, order2, order3);
        int count = TescoFleetContainers.maxOrdersFitting(CONTAINERS, PRODUCTS, orders, 1);
        // container 1 volume=6000: order1(1710) fits, order2(12000) no, order3(80) fits
        assertThat(count).isEqualTo(2);
    }

    @Test
    void maxOrdersFitting_largeContainer_allFit() {
        var order1 = List.of(new OrderLine(1, 3));  // 240
        var order2 = List.of(new OrderLine(2, 10)); // 12000
        var order3 = List.of(new OrderLine(3, 5));  // 1050

        List<List<OrderLine>> orders = List.of(order1, order2, order3);
        int count = TescoFleetContainers.maxOrdersFitting(CONTAINERS, PRODUCTS, orders, 3);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void maxOrdersFitting_noOrders() {
        int count = TescoFleetContainers.maxOrdersFitting(CONTAINERS, PRODUCTS, List.of(), 1);
        assertThat(count).isEqualTo(0);
    }
}
