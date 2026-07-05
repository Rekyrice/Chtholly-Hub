package com.chtholly.seed;

import java.time.LocalDate;
import java.util.List;

/**
 * Provides fixed seed personas for launch-day community content.
 */
public class SeedAccountGenerator {

    public List<SeedAccountProfile> accounts() {
        return List.of(
                new SeedAccountProfile(
                        "night-coder",
                        "夜猫子程序员",
                        "深夜写代码，白天补觉。主要记录 Java、Python 和后端踩坑。",
                        "/images/avatars/seed-night-coder.png",
                        "SECRET",
                        LocalDate.of(1998, 11, 3),
                        "北境理工",
                        List.of("Java", "Python", "后端", "深夜"),
                        "写 Java/Python 技术文章，深夜活跃"),
                new SeedAccountProfile(
                        "anime-critic",
                        "番剧评论家",
                        "每季追番，认真写观后感，也会偏心喜欢安静的作品。",
                        "/images/avatars/seed-anime-critic.png",
                        "SECRET",
                        LocalDate.of(2000, 4, 12),
                        "浮空岛影像研究会",
                        List.of("番剧", "观后感", "治愈", "季度追番"),
                        "写动画观后感，每季追番"),
                new SeedAccountProfile(
                        "moyu-master",
                        "摸鱼达人",
                        "擅长在忙碌里找一点空隙，写轻松随笔和日常吐槽。",
                        "/images/avatars/seed-moyu.png",
                        "SECRET",
                        LocalDate.of(1999, 7, 22),
                        "午后茶水间",
                        List.of("随笔", "生活", "摸鱼", "日常"),
                        "写轻松随笔，吐槽日常"),
                new SeedAccountProfile(
                        "design-sis",
                        "设计小姐姐",
                        "喜欢 UI、配色和那些让人愿意停留一会儿的小细节。",
                        "/images/avatars/seed-designer.png",
                        "FEMALE",
                        LocalDate.of(1997, 2, 14),
                        "星屑设计学院",
                        List.of("设计", "UI", "UX", "创意"),
                        "写 UI/UX 设计心得"),
                new SeedAccountProfile(
                        "algo-runner",
                        "算法选手",
                        "研究大模型和竞赛题，偶尔把复杂东西讲成人话。",
                        "/images/avatars/seed-algorithm.png",
                        "SECRET",
                        LocalDate.of(2001, 8, 9),
                        "云端实验室",
                        List.of("算法", "大模型", "竞赛", "机器学习"),
                        "写大模型算法研究和竞赛经验"),
                new SeedAccountProfile(
                        "photo-walker",
                        "摄影爱好者",
                        "带着相机到处走，记录光、云和很普通但会发亮的日子。",
                        "/images/avatars/seed-photo.png",
                        "SECRET",
                        LocalDate.of(1996, 5, 30),
                        "远行笔记",
                        List.of("摄影", "旅行", "生活", "构图"),
                        "写摄影技巧和旅行日记"),
                new SeedAccountProfile(
                        "indie-dev",
                        "独立游戏开发者",
                        "在 Unity 和 Godot 之间反复横跳，慢慢做自己的小游戏。",
                        "/images/avatars/seed-gamedev.png",
                        "SECRET",
                        LocalDate.of(1995, 12, 5),
                        "像素工坊",
                        List.of("游戏开发", "Unity", "Godot", "独立游戏"),
                        "写 Unity/Godot 开发日志"),
                new SeedAccountProfile(
                        "book-notes",
                        "读书笔记",
                        "读得不算快，但会认真记下被某句话打动的瞬间。",
                        "/images/avatars/seed-books.png",
                        "SECRET",
                        LocalDate.of(2002, 1, 18),
                        "旧书架",
                        List.of("读书", "书评", "清单", "摘抄"),
                        "写书评和阅读清单")
        );
    }
}
