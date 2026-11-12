package com.zhongbai233.net_music_can_play_bili.gui;

/** Homography between a planar texture's edge coordinates and four projected corners. */
final class ProjectedInputPlane {
    private final double a, b, c, d, e, f, g, h;

    ProjectedInputPlane(double x0, double y0, double x1, double y1,
            double x2, double y2, double x3, double y3) {
        double dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        double dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        double determinant = dx1 * dy2 - dx2 * dy1;
        boolean affine = Math.abs(dx3) + Math.abs(dy3) < 1.0e-10;
        g = affine ? 0 : (dx3 * dy2 - dx2 * dy3) / determinant;
        h = affine ? 0 : (dx1 * dy3 - dx3 * dy1) / determinant;
        a = x1 - x0 + g * x1;
        b = x3 - x0 + h * x3;
        c = x0;
        d = y1 - y0 + g * y1;
        e = y3 - y0 + h * y3;
        f = y0;
    }

    Point sample(double u, double v) {
        double w = g * u + h * v + 1;
        return new Point((a * u + b * v + c) / w, (d * u + e * v + f) / w);
    }

    Point toTexturePoint(double screenX, double screenY, double width, double height) {
        if (!Double.isFinite(screenX) || !Double.isFinite(screenY) || width <= 0 || height <= 0) {
            return null;
        }
        double aa = a - screenX * g, bb = b - screenX * h;
        double dd = d - screenY * g, ee = e - screenY * h;
        double determinant = aa * ee - bb * dd;
        if (!Double.isFinite(determinant) || Math.abs(determinant) < 1.0e-10) {
            return null;
        }
        double u = ((screenX - c) * ee - bb * (screenY - f)) / determinant;
        double v = (aa * (screenY - f) - (screenX - c) * dd) / determinant;
        if (!Double.isFinite(u) || !Double.isFinite(v) || u < -1.0e-6 || v < -1.0e-6
                || u > 1.000001 || v > 1.000001) {
            return null;
        }
        return new Point(Math.clamp(u, 0, 1) * width, Math.clamp(v, 0, 1) * height);
    }

    record Point(double x, double y) { }
}
