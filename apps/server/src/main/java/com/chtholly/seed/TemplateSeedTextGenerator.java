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

                ## 开头：先承认自己没看懂

                我现在越来越不相信“这个问题应该很简单”这种判断。尤其是深夜写代码的时候，脑子很容易把几个相似的问题揉在一起，然后给出一个看起来很合理的解释。上次我就是这样，看到超时就以为是接口慢，看到 CPU 抖了一下又怀疑线程池，最后绕了一圈才发现是一个很普通的批量任务把连接占住了。

                所以后来我给自己定了一个有点笨的流程：先把现象写下来，再写猜测，最后才写改动。这样做不酷，也不快，但至少不会在同一个地方反复撞墙。

                ## 排查顺序：让证据先说话

                我的处理顺序通常是这样：

                1. 先把复现条件写下来，不急着改。
                2. 再看日志和指标，确认瓶颈发生在哪一层。
                3. 最后才动代码，保证每一步都有回滚点。

                这里面最有用的是第二步。很多时候日志不是为了“证明我做对了”，而是为了在我想当然的时候拉我一下。比如下面这种小片段很普通，但它能让我至少知道耗时到底花在哪里：

                ```java
                long started = System.currentTimeMillis();
                try {
                    return service.call();
                } finally {
                    log.info("cost={}ms", System.currentTimeMillis() - started);
                }
                ```

                如果是 CLI 工具，我也会保留类似的分段耗时：

                ```java
                record StepCost(String name, long millis) {}

                List<StepCost> costs = new ArrayList<>();
                long readStarted = System.currentTimeMillis();
                List<String> lines = Files.readAllLines(input);
                costs.add(new StepCost("read", System.currentTimeMillis() - readStarted));

                long parseStarted = System.currentTimeMillis();
                List<Item> items = parse(lines);
                costs.add(new StepCost("parse", System.currentTimeMillis() - parseStarted));
                ```

                ## 中间的小坑：优化前先删掉错觉

                我以前很喜欢一上来就换框架、换库、换写法，觉得这样才像是在解决问题。现在会稍微克制一点。很多性能问题真正需要的不是“更高级的方案”，而是删掉一些错觉：是不是输入数据比想象中大，是不是某个缓存没有命中，是不是网络请求被串行等待了。

                这件事说起来有点朴素，但很实用。先把问题拆成可验证的小段，再决定要不要改架构。否则写出来的东西可能很漂亮，结果只是把 bug 换了一个更难找的位置。说实话，这种亏我吃过，不止一次。

                ## 小结：别急着证明自己聪明

                这次的小结是，先让系统说话，再让自己动手。深夜写代码的时候尤其如此，脑子会骗你，日志不会。

                如果以后再遇到类似问题，我大概还是会从最笨的记录开始：输入是什么、耗时在哪、改动前后差了多少。它不浪漫，但能让我少走很多弯路。嗯，也算是一种温柔吧，对未来的自己温柔一点。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String animeBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 看这部作品的时候，最在意的不是剧情推进有多快，而是那些停下来的瞬间。角色没有说出口的话、饭桌边短短的沉默、分别前故作轻松的玩笑，反而比很大的事件更容易留在记忆里。

                ## 先说结论：它不急

                我喜欢它的一点是，它没有急着告诉观众“你应该感动”。它只是把时间放在那里，让你自己慢慢意识到：原来这些普通的日常，回头看的时候会变得这么珍贵。

                有些作品会很用力地推着你哭，配乐、台词、镜头都在同一秒告诉你“这里很重要”。但我更喜欢那种轻一点的表达。它让人物先生活在那里，等你习惯了他们的说话方式、吃饭速度和一点点小脾气，再突然让你意识到：啊，原来我已经在意他们了。

                ## 我记住的是细节

                如果要给推荐理由，我会写三条：

                - 情绪很安静，不会用力过猛。
                - 角色之间的关系有细节，不只是设定。
                - 看完以后会想珍惜身边的人。

                我特别喜欢那些没有被解释得太满的镜头。比如一个人明明想挽留，却只说“路上小心”；明明已经很难过，还要开一个不太好笑的玩笑。现实里很多感情也是这样，不会整整齐齐地摆出来给人看，更多时候只是藏在一句很普通的话后面。

                ## 适合什么时候看

                它适合在晚上看，也适合在刚结束一段忙碌之后看。不要倍速，慢慢来就好。泡一杯热的东西，手机放远一点，让自己跟着角色一起把时间走完。

                如果你最近也在想“有些日子是不是就这样过去了”，那这类作品可能会轻轻回答你：不是的。普通日子也会留下痕迹，只是它们不太吵，需要你回头时才看见。

                ## 最后

                我不会说它适合所有人。有人会觉得节奏慢，有人会觉得它太安静。可如果你愿意给它一点耐心，它也会把一些很柔软的东西交给你。那种感觉不是被击中，更像是被轻轻放在心口。嗯，我挺喜欢这样的。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String designBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 最近一直在想，好的界面不一定要很抢眼。尤其是内容社区，用户真正想看的不是按钮有多漂亮，而是文字、图片和互动能不能自然地待在一起。

                ## 不要让设计一直喊话

                我会优先检查三件事：层级是否清楚、留白是否让人愿意读下去、颜色有没有在暗色模式里失去对比。很多页面的问题不是“不够设计”，而是太想证明自己被设计过了。

                这句话听起来有点毒，但我真的见过太多这样的页面：每个模块都像主角，每个按钮都想被点，每块卡片都在努力发光。结果用户进来以后反而不知道该看哪里，只能先关掉页面冷静一下。设计如果一直喊话，内容就没有地方呼吸了。

                ## 我会先看结构，再看装饰

                一个简单的判断方法是，把页面截图缩小到 25%%。如果缩小后还能看出标题、正文、操作区和推荐区，说明结构大概率是稳的。如果缩小后只剩下一团颜色，那就需要回到布局本身。

                我还会做一个很笨的检查：把所有图标和颜色都想象成灰色，只剩字号、间距和位置。如果这时候页面还清楚，那说明骨架是可靠的。如果必须靠颜色救回来，就要小心了。颜色当然重要，但它不应该承担所有解释工作。

                ## 暗色模式不是反色

                暗色模式尤其容易翻车。把背景涂黑只是第一步，真正难的是控制对比、层次和情绪。太黑会压住内容，太亮又会刺眼。壁纸、蒙版、卡片背景这些东西要一起看，不能单独调一个数值就觉得结束了。

                我最近比较喜欢让背景保留一点细节，再给文字区域足够稳定的底色。这样页面有气氛，但正文不会被牺牲。说到底，内容社区还是要让人读东西，不是让人猜字。

                ## 动效也要有分寸

                最近我也更喜欢克制一点的动效。它们不应该像烟花一样跳出来，而是像呼吸一样提醒你：页面还活着。比如淡入淡出、轻微上浮、按钮状态变化，这些小动作足够了。

                最后想说的是，安静不是没设计。安静其实更难，因为它要求你知道什么时候收手。这个判断很微妙，也很容易被需求打乱。唉，设计师的修行大概就是一边妥协，一边偷偷守住一点秩序吧。
                """.formatted(postPlan.title(), account.nickname());
    }

    private String essayBody(SeedAccountProfile account, SeedPostPlan postPlan) {
        return """
                # %s

                %s 今天想写一点不那么正式的东西。可能是路上看到的光，也可能是午休时忽然想起的一句话。它们都不算大事，但如果完全不记下来，好像又有点可惜。

                ## 开头：这不是什么大事

                我越来越觉得，生活里的恢复感不是突然变好，而是某个瞬间你发现自己终于能慢一点了。喝完一杯水，整理一张照片，读完两页书，或者只是把窗口关掉，安静十分钟。

                今天中午我本来只是想随便翻几页书，结果看到一句话停了很久。大意是说，人不是靠某个宏大的答案活下去的，更多时候是靠一些小小的秩序。比如固定的杯子，熟悉的歌，走过很多次的路，还有一个愿意听你慢慢说话的人。

                ## 具体一点的瞬间

                我想到最近几次让自己放松下来的时刻。一次是在黄昏时看到楼下的树影，被风吹得一块一块地晃；一次是在便利店买到刚补货的面包，虽然只是很普通的红豆味；还有一次是把桌面清空以后，突然觉得今天也不是完全乱掉了。

                这些事情写出来有点小题大做。可是人好像就是这样，需要靠一些小东西确认自己还在生活里，而不是只是在任务列表里移动。

                ## 跑题一下

                说到任务列表，我有时候真的很讨厌那种永远清不完的待办。明明已经做完很多事了，可只要列表还剩几项，就会觉得自己不够努力。后来我试着把“休息”也写进去，第一次写的时候还觉得很傻。结果意外有用，因为它提醒我：恢复不是偷懒，是维护系统。这个说法有点像技术人，嗯，借来用一下。

                ## 我会怎么记录

                如果是读书，我现在不会再逼自己写完整的读后感。看到喜欢的句子，就先抄下来，再在旁边写一句“为什么停在这里”。有时候理由很简单，比如“这句话让我想到上周的雨”，或者“这里像是在替我说话”。这些零散的旁注以后翻起来，反而比很工整的总结更有温度。

                如果是生活，我会写得更具体一点。不要只写“今天很累”，而是写“下班路上风很大，耳机里那首歌刚好播到副歌”。具体的东西会把记忆钉住。等过一阵子再看，你会想起来那天的光、味道和一点点心情，而不是只看见一个模糊的结论。

                ## 小小的结尾

                这篇就当作一个小小的记录吧。没有什么结论，也不需要很用力地表达。能把今天留下来一点，就已经不错了。

                如果你也刚好有点累，可以先不用急着变得积极。坐一会儿，听首歌，等心情自己慢慢回来。

                我喜欢这样的文字，不是为了证明什么，只是把一小段时间折起来，放进口袋里。以后再摸到的时候，会想起今天其实也有温柔的地方。
                """.formatted(postPlan.title(), account.nickname());
    }

    private static String trimSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "简介没有写得很满，但留白有时候也很好。";
        }
        return summary.length() <= 140 ? summary : summary.substring(0, 140) + "……";
    }
}
