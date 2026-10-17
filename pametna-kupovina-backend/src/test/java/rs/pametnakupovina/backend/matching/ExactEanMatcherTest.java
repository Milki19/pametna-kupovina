package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExactEanMatcherTest {

    @Test
    void aChainsShortCodePaddedWithZerosIsNotABarcode() {
        assertThat(ExactEanMatcher.isChainCode("0000000002288")).isTrue();
        assertThat(ExactEanMatcher.isChainCode("0000000052740")).isTrue();
    }

    @Test
    void realBarcodesStayBarcodes() {
        assertThat(ExactEanMatcher.isChainCode("8601234567899")).isFalse();
        // UPC-A and EAN-8 written as EAN-13.
        assertThat(ExactEanMatcher.isChainCode("0036000291452")).isFalse();
        assertThat(ExactEanMatcher.isChainCode("0000096385074")).isFalse();
        assertThat(ExactEanMatcher.isChainCode("96385074")).isFalse();
    }
}
