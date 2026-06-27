package com.chtholly.agent;

/** 工具参数定义，供 schema 声明与自动校验。 */
public record ParamDef(String description, Class<?> type, boolean required) {}
