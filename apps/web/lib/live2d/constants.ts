/** 珂朵莉 Live2D 模型路径（public 目录） */
export const CHTHOLLY_MODEL_URL = "/live2d/chtholly/chtholly.model.json";
export const CHTHOLLY_MODEL_BASE = "/live2d/chtholly/";
export const LIVE2D_CORE_SCRIPT = "/live2d/vendor/live2d.min.js";

/** 表情名（与 chtholly.model.json 中 expressions 一致） */
export const CHTHOLLY_EXPRESSION = {
  neutral: "f01.exp.json",
  smile: "f02.exp.json",
  sad: "f03.exp.json",
} as const;

export const CHTHOLLY_TEXTURE_FALLBACK = "/live2d/chtholly/chtholly.2048/texture_00.png";
