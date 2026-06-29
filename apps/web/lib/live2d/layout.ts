/** 各页面 Live2D 展示区的布局预设（按容器尺寸自适应缩放） */
export type Live2DLayoutPreset = "agent" | "hero" | "compact";

export type Live2DLayoutConfig = {
  /** 目标占容器高度比例，建议 ≥ 0.6 */
  fillRatio: number;
  /** 人物视觉中心纵向目标（0–1），0.5 正中，略大则偏下 */
  visualCenterY: number;
  /** 左右留白比例（单侧） */
  paddingX: number;
};

export const LIVE2D_LAYOUT_PRESETS: Record<Live2DLayoutPreset, Live2DLayoutConfig> = {
  /** Agent 工作台：约 76% 高度，视觉中心略低于正中 */
  agent: { fillRatio: 0.76, visualCenterY: 0.56, paddingX: 0.02 },
  /** 首页 Hero 等大区域 */
  hero: { fillRatio: 0.86, visualCenterY: 0.58, paddingX: 0.04 },
  /** 侧栏较窄或嵌入卡片 */
  compact: { fillRatio: 0.70, visualCenterY: 0.54, paddingX: 0.06 },
};

/** Live2D 舞台背景主题（通过 CSS 类名切换） */
export type Live2DBackgroundTheme = "dusk" | "aurora" | "twilight" | "soft";

export const LIVE2D_BACKGROUND_THEMES: {
  id: Live2DBackgroundTheme;
  label: string;
  description: string;
}[] = [
  { id: "dusk", label: "暮色", description: "深蓝到紫罗兰，默认" },
  { id: "aurora", label: "极光", description: "深蓝、靛青与品红渐变" },
  { id: "twilight", label: "薄暮", description: "紫灰到雾蓝，偏沉静" },
  { id: "soft", label: "晨雾", description: "浅天蓝与淡紫，明亮柔和" },
];

type FitTarget = {
  internalModel: { width: number; height: number };
  scale: { set: (value: number) => void };
  anchor: { set: (x: number, y: number) => void };
  x: number;
  y: number;
};

/** 按容器尺寸与预设，使模型约占 fillRatio 高度 */
export function fitLive2DModel(
  model: FitTarget,
  mount: { clientWidth: number; clientHeight: number },
  config: Live2DLayoutConfig,
) {
  const modelW = model.internalModel.width;
  const modelH = model.internalModel.height;
  if (!modelW || !modelH || mount.clientWidth <= 0 || mount.clientHeight <= 0) {
    return;
  }

  const availW = mount.clientWidth * (1 - config.paddingX * 2);
  const targetH = mount.clientHeight * config.fillRatio;
  const scale = Math.min(availW / modelW, targetH / modelH);
  const scaledH = modelH * scale;

  model.scale.set(scale);
  model.anchor.set(0.5, 1);
  model.x = mount.clientWidth / 2;
  // 锚点在脚底，按视觉中心定位（略低于 0.5 即居中偏下）
  model.y = mount.clientHeight * config.visualCenterY + scaledH / 2;
}
