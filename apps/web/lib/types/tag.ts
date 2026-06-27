/** 标签列表项（与后端 TagResponse 对齐） */
export type TagItem = {
  id: string;
  name: string;
  slug: string;
  usageCount: number;
};
