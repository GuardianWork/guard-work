package com.guardwork.backend.user;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountPhoneTest {
    @Test
    void normalizesEquivalentVietnameseMobileFormsAndKeepsAbsence() {
        assertThat(AccountPhone.normalize(" 0901234567 ")).isEqualTo("+84901234567");
        assertThat(AccountPhone.normalize("+84901234567")).isEqualTo("+84901234567");
        assertThat(AccountPhone.normalize(null)).isNull();
    }

    @Test
    void rejectsBlankLandlineAndMalformedValues() {
        for (String value : new String[]{"", " ", "0123456789", "0241234567", "84901234567", "+840901234567", "090 123 4567"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> AccountPhone.normalize(value));
        }
    }
}
