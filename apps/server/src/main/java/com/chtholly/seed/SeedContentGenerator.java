package com.chtholly.seed;

import java.util.List;
import java.util.Map;

/**
 * Builds deterministic post plans for each seed persona.
 */
public class SeedContentGenerator {

    private static final Map<String, List<SeedPostPlan>> PLANS = Map.of(
            "night-coder", List.of(
                    post("凌晨两点的 Java 线程池排查记录：别急着调参数", "技术", List.of("技术", "Java", "后端"), 13, 1),
                    post("用 Python 写一个小工具整理番剧截图，我顺手踩了三个坑", "技术", List.of("技术", "Python", "效率"), 10, 2),
                    post("接口超时排查：从日志到连接池，再到一次误判", "技术", List.of("后端", "排查", "实践"), 6, 3),
                    post("聊聊最近用 Rust 重写一个 CLI 工具的体验", "技术", List.of("技术", "Rust", "CLI"), 2, 4)),
            "anime-critic", List.of(
                    post("《葬送的芙莉莲》：时间慢下来以后，日常才变得清楚", "番剧", List.of("番剧", "观后感", "芙莉莲"), 12, 1),
                    post("为什么我会反复推荐《夏目友人帐》：温柔不是没有重量", "番剧", List.of("番剧", "治愈", "夏目友人帐"), 8, 2),
                    post("本季追番清单：安静一点也没关系", "番剧", List.of("番剧", "季度追番", "推荐"), 4, 3),
                    post("从《轻音少女》聊到日常系：那些没有大事件的好看", "番剧", List.of("番剧", "日常系", "轻音少女"), 1, 4)),
            "moyu-master", List.of(
                    post("如何在很忙的一天里偷偷喘口气：我的低成本恢复法", "随笔", List.of("随笔", "生活", "摸鱼"), 11, 1),
                    post("午休时看云，也算一种恢复吧", "随笔", List.of("生活", "日常", "放松"), 7, 2),
                    post("我整理了一份轻松 BGM 清单，适合不想努力的傍晚", "随笔", List.of("清单", "摸鱼", "音乐"), 3, 3),
                    post("把待办列表折起来半小时，世界没有塌下来", "随笔", List.of("生活", "恢复", "日常"), 1, 4)),
            "design-sis", List.of(
                    post("让内容社区更安静的 UI 细节：少一点喊话，多一点停留", "设计", List.of("设计", "UI", "社区"), 14, 1),
                    post("夜间模式不是把页面涂黑就好：壁纸、蒙版和文字的平衡", "设计", List.of("设计", "暗色模式", "UX"), 9, 2),
                    post("我常用的 5 个配色检查习惯，从截图缩小开始", "设计", List.of("配色", "设计", "工具"), 5, 3),
                    post("为什么我喜欢 8px 圆角：内容产品里的克制感", "设计", List.of("设计", "组件", "视觉"), 2, 4)),
            "algo-runner", List.of(
                    post("从 RAG 到 Agent：我理解的检索增强，不只是把资料塞进 prompt", "技术", List.of("大模型", "RAG", "Agent"), 13, 1),
                    post("竞赛题复盘：不要急着写第一版，先把反例想清楚", "技术", List.of("算法", "竞赛", "复盘"), 8, 2),
                    post("Embedding 相似度为什么会骗你：一次语义去重的小实验", "技术", List.of("Embedding", "向量检索", "大模型"), 2, 3),
                    post("把一道动态规划题讲成人话：状态定义比转移更重要", "技术", List.of("算法", "动态规划", "复盘"), 1, 4)),
            "photo-walker", List.of(
                    post("黄昏时拍照，先等一等光：我最近最喜欢的十分钟", "生活", List.of("摄影", "旅行", "构图"), 12, 1),
                    post("一条旧街道的照片整理：为什么我舍不得删掉虚焦的那张", "生活", List.of("摄影", "生活", "旅行"), 9, 2),
                    post("新手也能用的三种构图练习，从门口的便利店开始", "生活", List.of("摄影", "技巧", "入门"), 4, 3),
                    post("旅行日记不一定要远方：周末去河边走了一圈", "生活", List.of("摄影", "旅行", "日记"), 1, 4)),
            "indie-dev", List.of(
                    post("Godot 小游戏开发日志：先让角色动起来，再谈梦想", "技术", List.of("Godot", "游戏开发", "日志"), 10, 1),
                    post("Unity 原型阶段我踩过的资源管理坑：临时文件最后都会来找你", "技术", List.of("Unity", "独立游戏", "实践"), 6, 2),
                    post("给小游戏做手感，比想象中更难：跳跃高度改了二十次", "技术", List.of("游戏开发", "手感", "设计"), 1, 3),
                    post("一周做不完 Demo 的时候，我决定先砍掉两个系统", "技术", List.of("独立游戏", "开发日志", "取舍"), 0, 4)),
            "book-notes", List.of(
                    post("最近读到的五句话：有些句子会在很久以后回来", "生活", List.of("读书", "摘抄", "随笔"), 11, 1),
                    post("一本适合睡前慢慢看的书：不要急着读完", "生活", List.of("书评", "阅读", "推荐"), 7, 2),
                    post("我的七月阅读清单：小说、散文和一点点历史", "生活", List.of("读书", "清单", "生活"), 2, 3),
                    post("读书笔记到底要不要写得很完整？我的答案变了", "生活", List.of("读书", "笔记", "方法"), 1, 4))
    );

    public List<SeedPostPlan> postsFor(SeedAccountProfile account) {
        return PLANS.getOrDefault(account.handle(), List.of(
                post(account.nickname() + "的第一篇仓库记录：为什么想把这些事写下来", "随笔", account.tags(), 7, 1),
                post(account.nickname() + "的最近想法：从一个很小的细节开始", "随笔", account.tags(), 5, 2),
                post(account.nickname() + "的推荐清单：最近值得慢慢看的东西", "随笔", account.tags(), 3, 3),
                post(account.nickname() + "的周末复盘：一点生活，一点跑题", "随笔", account.tags(), 1, 4)));
    }

    private static SeedPostPlan post(String title, String category, List<String> tags, int daysAgo, int slot) {
        return new SeedPostPlan(title, category, tags, daysAgo, slot);
    }
}
