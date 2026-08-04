package com.chtholly.relation.util;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the public and legacy relation-cursor contract. */
class RelationCursorTest {

    @Test
    void compositeCursorRoundTripsBothOrderingComponents() {
        String encoded = RelationCursor.encode(1_234L, 22L);

        assertThat(RelationCursor.require(encoded))
                .isEqualTo(new RelationCursor.RelationCursorPoint(
                        1_234L, 22L));
    }

    @Test
    void decimalCursorKeepsLegacyMillisecondCompatibility() {
        assertThat(RelationCursor.require("1234"))
                .isEqualTo(new RelationCursor.RelationCursorPoint(
                        1_234L, null));
    }

    @Test
    void malformedCursorUsesTheStableBadRequestError() {
        assertThatThrownBy(() -> RelationCursor.require("not-a-cursor"))
                .isInstanceOf(BusinessException.class)
                .extracting(failure ->
                        ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
