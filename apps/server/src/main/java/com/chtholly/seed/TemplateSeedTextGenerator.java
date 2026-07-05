package com.chtholly.seed;

import org.springframework.stereotype.Component;

/**
 * Deterministic fallback text generator for seed data.
 */
@Component
public class TemplateSeedTextGenerator implements SeedTextGenerator {

    @Override
    public String bangumiReview(BangumiSubjectSeed subject) {
        return """
                我会把《%s》放在一个不太吵的位置推荐给你。它的评分是 %.1f，说明很多人也认真看见了它。
                %s
                如果某天你想慢慢看一个故事，不急着被结论推着走，可以试试它。嗯，我觉得这种故事适合被好好记住。
                """.formatted(subject.title(), subject.score(), trimSummary(subject.summary()));
    }

    @Override
    public String postBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return switch (postPlan.category()) {
            case "技术" -> technicalBody(account, postPlan);
            case "番剧" -> animeBody(account, postPlan);
            case "设计" -> designBody(account, postPlan);
            default -> essayBody(account, postPlan);
        };
    }

    @Override
    public String comment(SeedAccountProfile commenter, SeedAccountProfile author, SeedPostPlan postPlan) {
        return "读完以后挺有共鸣的，尤其是你写到「" + postPlan.title() + "」这一段。"
                + commenter.nickname() + "觉得这里可以再展开一点，应该会很有意思。";
    }

    private String technicalBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 最近整理了一次实践记录。问题一开始看起来不大：只是一次接口变慢、一次构建失败，或者一个工具脚本跑得不够稳定。可是往下查的时候才发现，真正麻烦的地方往往不是代码本身，而是我们太快相信了第一眼看到的现象。

                我的处理顺序通常是：

                1. 先把复现条件写下来，不急着改。
                2. 再看日志和指标，确认瓶颈发生在哪一层。
                3. 最后才动代码，保证每一步都有回滚点。

                ```java
                long started = System.currentTimeMillis();
                try {
                    return service.call();
                } finally {
                    log.info("cost={}ms", System.currentTimeMillis() - started);
                }
                ```

                这段代码很普通，但它提醒我：没有观测，就没有判断。很多时候我们以为自己在优化，其实只是在猜。等数据摆出来以后，问题会安静很多。

                这次的小结是，先让系统说话，再让自己动手。深夜写代码的时候尤其如此，脑子会骗你，日志不会。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String animeBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 看这部作品的时候，最在意的不是剧情推进有多快，而是那些停下来的瞬间。角色没有说出口的话、饭桌边短短的沉默、分别前故作轻松的玩笑，反而比很大的事件更容易留在记忆里。

                我喜欢它的一点是，它没有急着告诉观众“你应该感动”。它只是把时间放在那里，让你自己慢慢意识到：原来这些普通的日常，回头看的时候会变得这么珍贵。

                如果要给推荐理由，我会写三条：

                - 情绪很安静，不会用力过猛。
                - 角色之间的关系有细节，不只是设定。
                - 看完以后会想珍惜身边的人。

                适合在晚上看，也适合在刚结束一段忙碌之后看。不要倍速，慢慢来就好。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String designBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 最近一直在想，好的界面不一定要很抢眼。尤其是内容社区，用户真正想看的不是按钮有多漂亮，而是文字、图片和互动能不能自然地待在一起。

                我会优先检查三件事：层级是否清楚、留白是否让人愿意读下去、颜色有没有在暗色模式里失去对比。很多页面的问题不是“不够设计”，而是太想证明自己被设计过了。

                一个简单的判断方法是，把页面截图缩小到 25%%。如果缩小后还能看出标题、正文、操作区和推荐区，说明结构大概率是稳的。如果缩小后只剩下一团颜色，那就需要回到布局本身。

                最近我也更喜欢克制一点的动效。它们不应该像烟花一样跳出来，而是像呼吸一样提醒你：页面还活着。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String essayBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 今天想写一点不那么正式的东西。可能是路上看到的光，也可能是午休时忽然想起的一句话。它们都不算大事，但如果完全不记下来，好像又有点可惜。

                我越来越觉得，生活里的恢复感不是突然变好，而是某个瞬间你发现自己终于能慢一点了。喝完一杯水，整理一张照片，读完两页书，或者只是把窗口关掉，安静十分钟。

                这篇就当作一个小小的记录吧。没有什么结论，也不需要很用力地表达。能把今天留下来一点，就已经不错了。

                如果你也刚好有点累，可以先不用急着变得积极。坐一会儿，听首歌，等心情自己慢慢回来。
                """.formatted(postPlan.title(), account.nickname());
    }

    private static String trimSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "简介没有写得很满，但留白有时候也很好。";
        }
        return summary.length() <= 140 ? summary : summary.substring(0, 140) + "……";
    }
}
