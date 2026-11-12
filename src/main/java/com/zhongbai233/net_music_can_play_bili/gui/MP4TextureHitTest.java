package com.zhongbai233.net_music_can_play_bili.gui;

/** Hit routing over the actual rendered layout snapshot, independent of Minecraft lifecycle. */
final class MP4TextureHitTest {
        private static final int TEXTURE_W = 256, TEXTURE_H = 448;
        record View(boolean landscape, boolean controlsVisible, boolean playlistOpen,
                boolean subtitleMenuOpen, boolean qualityMenuOpen, int queueSize, int queueScrollOffset) { }
        private MP4TextureHitTest() {
        }

        static MP4TextureHit hit(View view, TexturePoint point) {
            return view.landscape() ? landscapeHit(view, toLandscape(point)) : portraitHit(view, point);
        }

        static float volumeAt(View view, TexturePoint point) {
            if (view.landscape()) {
                TexturePoint landscape = toLandscape(point);
                int x0 = 348;
                int w = 70;
                return (landscape.x() - x0) / (float) Math.max(1, w);
            }
            int x0 = 64;
            int w = 154;
            return (point.x() - x0) / (float) Math.max(1, w);
        }

        static float progressAt(View view, TexturePoint point) {
            if (view.landscape()) {
                TexturePoint landscape = toLandscape(point);
                int x0 = 32;
                int w = 384;
                return (landscape.x() - x0) / (float) Math.max(1, w);
            }
            int x0 = 28;
            int w = 200;
            return (point.x() - x0) / (float) Math.max(1, w);
        }

        static int queueRowAt(View view, TexturePoint point) {
            if (view.landscape()) {
                TexturePoint landscape = toLandscape(point);
                if (!inside(landscape, 88, 75, 248, 43)) {
                    return -1;
                }
                int row = Math.max(0, Math.min(3 - 1, (int) ((landscape.y() - 75) / 14)));
                return view.queueScrollOffset() + row < view.queueSize() ? row : -1;
            }
            if (!inside(point, 31, 116, TEXTURE_W - 70, 168)) {
                return -1;
            }
            int row = Math.max(0, Math.min(6 - 1, (int) ((point.y() - 116) / 28)));
            return view.queueScrollOffset() + row < view.queueSize() ? row : -1;
        }

        static boolean rotationHintConfirm(View view, TexturePoint point) {
            if (view.landscape()) {
                TexturePoint landscape = toLandscape(point);
                return inside(landscape, 174, 139, 100, 26);
            }
            return inside(point, 78, 232, 100, 26);
        }

        private static MP4TextureHit portraitHit(View view, TexturePoint p) {
            if (view.qualityMenuOpen()) {
                for (int i = 0; i < 8; i++) {
                    if (inside(p, 160, 68 + i * 13, 66, 11)) {
                        return qualityHit(i);
                    }
                }
                if (inside(p, 150, 46, 86, 132)) {
                    return MP4TextureHit.NONE;
                }
            }
            if (view.subtitleMenuOpen()) {
                if (inside(p, 146, 354, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_OFF;
                }
                if (inside(p, 146, 375, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_PRIMARY;
                }
                if (inside(p, 146, 396, 44, 14)) {
                    return MP4TextureHit.SUBTITLE_SECONDARY;
                }
                if (inside(p, 146, 417, 72, 14)) {
                    return MP4TextureHit.SUBTITLE_AI;
                }
            }
            if (view.subtitleMenuOpen() && inside(p, 132, 322, 96, 118)) {
                return MP4TextureHit.NONE;
            }
            if (view.playlistOpen() && inside(p, 18, 62, TEXTURE_W - 36, 242)) {
                return MP4TextureHit.PLAYLIST_AREA;
            }
            if (inside(p, 193, 21, 42, 22)) {
                return MP4TextureHit.QUALITY;
            }
            if (inside(p, 34, 333, 42, 42)) {
                return MP4TextureHit.PREVIOUS;
            }
            if (inside(p, 101, 325, 54, 54)) {
                return MP4TextureHit.PLAY;
            }
            if (inside(p, 180, 333, 42, 42)) {
                return MP4TextureHit.NEXT;
            }
            if (inside(p, 28, 292, 200, 16)) {
                return MP4TextureHit.PROGRESS;
            }
            if (inside(p, 56, 386, 172, 24)) {
                return MP4TextureHit.VOLUME;
            }
            if (inside(p, 20, 411, 42, 14)) {
                return MP4TextureHit.BILI_LOGIN;
            }
            if (inside(p, 68, 411, 58, 14)) {
                return MP4TextureHit.SHUFFLE;
            }
            if (inside(p, 136, 411, 58, 14)) {
                return MP4TextureHit.PLAYLIST;
            }
            if (inside(p, 203, 411, 32, 14)) {
                return MP4TextureHit.LYRICS;
            }
            return MP4TextureHit.NONE;
        }

        private static MP4TextureHit landscapeHit(View view, TexturePoint p) {
            if (view.controlsVisible()) {
                if (view.qualityMenuOpen()) {
                    for (int i = 0; i < 8; i++) {
                        if (inside(p, 330, 65 + i * 13, 70, 11)) {
                            return qualityHit(i);
                        }
                    }
                }
                if (view.qualityMenuOpen() && inside(p, 318, 42, 94, 132)) {
                    return MP4TextureHit.NONE;
                }
                if (view.subtitleMenuOpen()) {
                    if (inside(p, 244, 72, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_OFF;
                    }
                    if (inside(p, 244, 94, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_PRIMARY;
                    }
                    if (inside(p, 244, 116, 52, 14)) {
                        return MP4TextureHit.SUBTITLE_SECONDARY;
                    }
                    if (inside(p, 244, 138, 82, 14)) {
                        return MP4TextureHit.SUBTITLE_AI;
                    }
                }
                if (view.subtitleMenuOpen() && inside(p, 232, 48, 108, 114)) {
                    return MP4TextureHit.NONE;
                }
                if (inside(p, 330, 14, 52, 28)) {
                    return MP4TextureHit.QUALITY;
                }
                if (inside(p, 278, 16, 44, 20)) {
                    return MP4TextureHit.PLAYLIST;
                }
                if (inside(p, 28, 207, 48, 24)) {
                    return MP4TextureHit.LYRICS;
                }
                if (inside(p, 146, 204, 30, 24)) {
                    return MP4TextureHit.PREVIOUS;
                }
                if (inside(p, 200, 198, 46, 34)) {
                    return MP4TextureHit.PLAY;
                }
                if (inside(p, 270, 204, 30, 24)) {
                    return MP4TextureHit.NEXT;
                }
                if (inside(p, 32, 176, 384, 18)) {
                    return MP4TextureHit.PROGRESS;
                }
                if (inside(p, 340, 202, 86, 22)) {
                    return MP4TextureHit.VOLUME;
                }
                if (inside(p, 88, 213, 48, 14)) {
                    return MP4TextureHit.REPEAT;
                }
                if (view.playlistOpen() && inside(p, 72, 44, 304, 76)) {
                    return MP4TextureHit.PLAYLIST_AREA;
                }
            }
            if (inside(p, 10, 10, TEXTURE_H - 20, TEXTURE_W - 20)) {
                return MP4TextureHit.VIDEO_AREA;
            }
            return MP4TextureHit.NONE;
        }

        private static MP4TextureHit qualityHit(int index) {
            return switch (Math.max(0, Math.min(7, index))) {
                case 0 -> MP4TextureHit.QUALITY_0;
                case 1 -> MP4TextureHit.QUALITY_1;
                case 2 -> MP4TextureHit.QUALITY_2;
                case 3 -> MP4TextureHit.QUALITY_3;
                case 4 -> MP4TextureHit.QUALITY_4;
                case 5 -> MP4TextureHit.QUALITY_5;
                case 6 -> MP4TextureHit.QUALITY_6;
                default -> MP4TextureHit.QUALITY_7;
            };
        }

        private static TexturePoint toLandscape(TexturePoint point) {
            return new TexturePoint(TEXTURE_H - point.y(), point.x());
        }

        private static boolean inside(TexturePoint p, int x, int y, int w, int h) {
            return p.x() >= x && p.y() >= y && p.x() < x + w && p.y() < y + h;
        }
    }

    enum MP4TextureHit {
        NONE,
        MEDIA,
        VIDEO_AREA,
        PLAY,
        PREVIOUS,
        NEXT,
        PROGRESS,
        VOLUME,
        SHUFFLE,
        REPEAT,
        PLAYLIST,
        LYRICS,
        BILI_LOGIN,
        SUBTITLE_PRIMARY,
        SUBTITLE_SECONDARY,
        SUBTITLE_OFF,
        SUBTITLE_AI,
        QUALITY,
        QUALITY_0,
        QUALITY_1,
        QUALITY_2,
        QUALITY_3,
        QUALITY_4,
        QUALITY_5,
        QUALITY_6,
        QUALITY_7,
        PLAYLIST_AREA
    }


record TexturePoint(float x, float y) { }
