package com.chtholly.integration;

import com.chtholly.relation.service.RelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden-path contracts for Following, Outbox and follower projection updates.
 */
class RelationGoldenPathIT extends AbstractGoldenPathIT {

    private static final long FROM_USER_ID = 201L;
    private static final long TO_USER_ID = 202L;

    @Autowired
    private RelationService relationService;

    @BeforeEach
    void setUpData() {
        cleanRedis();
        cleanDatabase();
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                FROM_USER_ID, "Follower", "integration-follower");
        jdbc.update("INSERT INTO users (id, nickname, handle) VALUES (?, ?, ?)",
                TO_USER_ID, "Target", "integration-target");
    }

    @Test
    void rollsBackFollowingWhenOutboxInsertFails() {
        jdbc.execute("""
                CREATE TRIGGER it_fail_outbox BEFORE INSERT ON outbox
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced outbox failure'
                """);
        try {
            assertThatThrownBy(() -> relationService.follow(FROM_USER_ID, TO_USER_ID))
                    .hasMessageContaining("forced outbox failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_outbox");
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM following
                WHERE from_user_id = ? AND to_user_id = ?
                """, Long.class, FROM_USER_ID, TO_USER_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
    }
}
