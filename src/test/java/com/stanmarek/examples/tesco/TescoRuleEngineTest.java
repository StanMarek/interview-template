package com.stanmarek.examples.tesco;

import com.stanmarek.examples.tesco.TescoRuleEngine.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stanmarek.examples.tesco.TescoRuleEngine.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

class TescoRuleEngineTest {

    @Test
    void exampleFromProblem_met() {
        var cart = List.of(
                new OrderItem(1, "Paracetamol", 3),
                new OrderItem(2, "analgesic", 3),
                new OrderItem(3, "chocolate", 8),
                new OrderItem(4, "Paracetamol", 2)
        );
        var rules = List.<Rule>of(
                new BulkBuyLimit(10),
                new BulkBuyLimitCategory("Paracetamol", 5)
        );
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(MET);
    }

    @Test
    void bulkBuyLimitBreached() {
        var cart = List.of(
                new OrderItem(1, "chocolate", 11),
                new OrderItem(2, "crisps", 5)
        );
        var rules = List.<Rule>of(new BulkBuyLimit(10));
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(BREACHED);
    }

    @Test
    void bulkBuyLimitExactlyAtLimit() {
        var cart = List.of(
                new OrderItem(1, "chocolate", 10),
                new OrderItem(2, "crisps", 8)
        );
        var rules = List.<Rule>of(new BulkBuyLimit(10));
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(MET);
    }

    @Test
    void categoryLimitBreached() {
        var cart = List.of(
                new OrderItem(1, "Paracetamol", 3),
                new OrderItem(4, "Paracetamol", 3)
        );
        var rules = List.<Rule>of(
                new BulkBuyLimit(100),
                new BulkBuyLimitCategory("Paracetamol", 5)
        );
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(BREACHED);
    }

    @Test
    void categoryMatchIsCaseInsensitive() {
        var cart = List.of(
                new OrderItem(1, "PARACETAMOL", 3),
                new OrderItem(2, "paracetamol", 2)
        );
        var rules = List.<Rule>of(
                new BulkBuyLimitCategory("Paracetamol", 5)
        );
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(MET);
    }

    @Test
    void emptyCart() {
        var rules = List.<Rule>of(
                new BulkBuyLimit(10),
                new BulkBuyLimitCategory("Paracetamol", 5)
        );
        assertThat(TescoRuleEngine.checkRules(List.of(), rules)).isEqualTo(MET);
    }

    @Test
    void noRules() {
        var cart = List.of(new OrderItem(1, "anything", 1000));
        assertThat(TescoRuleEngine.checkRules(cart, List.of())).isEqualTo(MET);
    }

    @Test
    void multipleRulesFirstBreached() {
        var cart = List.of(
                new OrderItem(1, "Paracetamol", 3),
                new OrderItem(2, "Paracetamol", 4)
        );
        var rules = List.<Rule>of(
                new BulkBuyLimitCategory("Paracetamol", 5),
                new BulkBuyLimit(100)
        );
        assertThat(TescoRuleEngine.checkRules(cart, rules)).isEqualTo(BREACHED);
    }
}
