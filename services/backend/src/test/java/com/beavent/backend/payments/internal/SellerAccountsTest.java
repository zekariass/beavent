package com.beavent.backend.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beavent.backend.platform.ApiException;
import org.junit.jupiter.api.Test;

/** What counts as the business's own account: its name, a bank number, a wallet number. */
class SellerAccountsTest {

    @Test
    void theHolderMustBeTheRegisteredBusinessIgnoringCasePunctuationAndCompanyEndings() {
        assertThat(SellerAccounts.sameName("Abeba Beauty PLC", "Abeba Beauty PLC"))
                .isTrue();
        assertThat(SellerAccounts.sameName("ABEBA BEAUTY", "Abeba Beauty PLC")).isTrue();
        assertThat(SellerAccounts.sameName("Abeba  Beauty, P.L.C.", "Abeba Beauty PLC"))
                .isTrue();
        assertThat(SellerAccounts.sameName("አበባ ውበት", "አበባ ውበት")).isTrue();
        assertThat(SellerAccounts.sameName("Abeba Beauty Salon", "Abeba Beauty PLC"))
                .isFalse();
        assertThat(SellerAccounts.sameName("Dawit Tesfaye", "Abeba Beauty PLC")).isFalse();
        assertThat(SellerAccounts.sameName("  ", "Abeba Beauty PLC")).isFalse();
        assertThat(SellerAccounts.sameName("PLC", "PLC"))
                .as("a name that is only an ending is no name")
                .isFalse();
    }

    @Test
    void bankNumbersAreDigitsAndWalletsAreEthiopianMobileNumbers() {
        assertThat(SellerAccounts.normalizeNumber("BANK", "1000 1234-56789")).isEqualTo("1000123456789");
        assertThat(SellerAccounts.normalizeNumber("MOBILE_MONEY", "0911 223 344"))
                .isEqualTo("251911223344");
        assertThat(SellerAccounts.normalizeNumber("MOBILE_MONEY", "+251711223344"))
                .isEqualTo("251711223344");
        assertThatThrownBy(() -> SellerAccounts.normalizeNumber("BANK", "12345"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SellerAccounts.normalizeNumber("BANK", "10001234567AB"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SellerAccounts.normalizeNumber("MOBILE_MONEY", "0111223344"))
                .isInstanceOf(ApiException.class);
    }
}
