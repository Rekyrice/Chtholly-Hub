package com.chtholly.agent.search;

import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.chtholly.bangumi.service.BangumiService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalInfrastructureTestConfigTest {

    @Test
    void deterministicEntityAdapterPreservesTheRequestedEntityName() {
        BangumiService service = new RetrievalInfrastructureTestConfig().deterministicBangumiService();

        assertThat(service.search("缓存一致性", 5))
                .singleElement()
                .extracting(BangumiSubjectRow::getNameCn)
                .isEqualTo("缓存一致性");
        assertThat(service.search("站内资料如何解释“缓存一致性”涉及的实体关系？", 5))
                .singleElement()
                .extracting(BangumiSubjectRow::getNameCn)
                .isEqualTo("缓存一致性");
        assertThat(service.search(
                "站内是否有证据证明“只删除详情缓存就够了”？没有时请返回证据不足。", 5))
                .isEmpty();
    }
}
