package com.chtholly.seed;

import java.util.List;
import java.util.Map;

/**
 * Builds deterministic post plans for each seed persona.
 */
public class SeedContentGenerator {

    private static final Map<String, List<SeedPostPlan>> PLANS = Map.of(
            "night-coder", List.of(
                    post("凌晨两点的 Java 线程池排查记录", "技术", List.of("技术", "Java", "后端"), 13, 1),
                    post("用 Python 写一个小工具整理番剧截图", "技术", List.of("技术", "Python", "效率"), 10, 2),
                    post("接口超时排查：从日志到连接池", "技术", List.of("后端", "排查", "实践"), 6, 3)),
            "anime-critic", List.of(
                    post("《葬送的芙莉莲》：时间慢下来以后", "番剧", List.of("番剧", "观后感", "芙莉莲"), 12, 1),
                    post("为什么我会反复推荐《夏目友人帐》", "番剧", List.of("番剧", "治愈", "夏目友人帐"), 8, 2),
                    post("本季追番清单：安静一点也没关系", "番剧", List.of("番剧", "季度追番", "推荐"), 4, 3)),
            "moyu-master", List.of(
                    post("如何在很忙的一天里偷偷喘口气", "随笔", List.of("随笔", "生活", "摸鱼"), 11, 1),
                    post("午休时看云，也算一种恢复吧", "随笔", List.of("生活", "日常", "放松"), 7, 2),
                    post("我整理了一份轻松 BGM 清单", "随笔", List.of("清单", "摸鱼", "音乐"), 3, 3)),
            "design-sis", List.of(
                    post("让内容社区更安静的 UI 细节", "设计", List.of("设计", "UI", "社区"), 14, 1),
                    post("夜间模式不是把页面涂黑就好", "设计", List.of("设计", "暗色模式", "UX"), 9, 2),
                    post("我常用的 5 个配色检查习惯", "设计", List.of("配色", "设计", "工具"), 5, 3)),
            "algo-runner", List.of(
                    post("从 RAG 到 Agent：我理解的检索增强", "技术", List.of("大模型", "RAG", "Agent"), 13, 1),
                    post("竞赛题复盘：不要急着写第一版", "技术", List.of("算法", "竞赛", "复盘"), 8, 2),
                    post("Embedding 相似度为什么会骗你", "技术", List.of("Embedding", "向量检索", "大模型"), 2, 3)),
            "photo-walker", List.of(
                    post("黄昏时拍照，先等一等光", "生活", List.of("摄影", "旅行", "构图"), 12, 1),
                    post("一条旧街道的照片整理", "生活", List.of("摄影", "生活", "旅行"), 9, 2),
                    post("新手也能用的三种构图练习", "生活", List.of("摄影", "技巧", "入门"), 4, 3)),
            "indie-dev", List.of(
                    post("Godot 小游戏开发日志：先让角色动起来", "技术", List.of("Godot", "游戏开发", "日志"), 10, 1),
                    post("Unity 原型阶段我踩过的资源管理坑", "技术", List.of("Unity", "独立游戏", "实践"), 6, 2),
                    post("给小游戏做手感，比想象中更难", "技术", List.of("游戏开发", "手感", "设计"), 1, 3)),
            "book-notes", List.of(
                    post("最近读到的五句话", "生活", List.of("读书", "摘抄", "随笔"), 11, 1),
                    post("一本适合睡前慢慢看的书", "生活", List.of("书评", "阅读", "推荐"), 7, 2),
                    post("我的七月阅读清单", "生活", List.of("读书", "清单", "生活"), 2, 3))
    );

    public List<SeedPostPlan> postsFor(SeedAccountProfile account) {
        return PLANS.getOrDefault(account.handle(), List.of(
                post(account.nickname() + "的第一篇仓库记录", "随笔", account.tags(), 7, 1),
                post(account.nickname() + "的最近想法", "随笔", account.tags(), 3, 2),
                post(account.nickname() + "的推荐清单", "随笔", account.tags(), 1, 3)));
    }

    private static SeedPostPlan post(String title, String category, List<String> tags, int daysAgo, int slot) {
        return new SeedPostPlan(title, category, tags, daysAgo, slot);
    }
}
