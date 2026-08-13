package com.zhongbai233.scene_editor.core.projection;

/** 世界点投影到 GUI 视口后的不可变结果。 */
public record ProjectedPoint(double screenX, double screenY, double depth, boolean visible, boolean behindCamera) {
}