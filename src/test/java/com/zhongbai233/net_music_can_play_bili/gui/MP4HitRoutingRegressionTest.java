package com.zhongbai233.net_music_can_play_bili.gui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MP4HitRoutingRegressionTest {
    private MP4TextureHitTest.View portrait(boolean playlist) {
        return new MP4TextureHitTest.View(false, true, playlist, false, false, 6, 0);
    }
    @Test void playlistBackgroundConsumesClickAboveCoveredProgressBar() {
        assertEquals(MP4TextureHit.PLAYLIST_AREA, MP4TextureHitTest.hit(portrait(true), new TexturePoint(100,298)));
    }
    @Test void progressBarWorksWhenPlaylistIsClosed() {
        assertEquals(MP4TextureHit.PROGRESS, MP4TextureHitTest.hit(portrait(false), new TexturePoint(100,298)));
    }
    @Test void intentionalExpandedRowGapRemainsClickable() {
        assertEquals(0,MP4TextureHitTest.queueRowAt(portrait(true),new TexturePoint(100,140)));
    }
    @Test void renderedPortraitLayoutUsesPortraitControls() {
        assertEquals(MP4TextureHit.PLAY, MP4TextureHitTest.hit(portrait(false),new TexturePoint(128,350)));
    }
    @Test void renderedLandscapeLayoutUsesLandscapeControls() {
        var view = new MP4TextureHitTest.View(true,true,false,false,false, 3, 0);
        assertEquals(MP4TextureHit.PLAY, MP4TextureHitTest.hit(view,new TexturePoint(215,225)));
    }
    @Test void subtitleBackgroundCannotTriggerTheCoveredNextButton() {
        var view = new MP4TextureHitTest.View(false,true,false,true,false, 6, 0);
        assertEquals(MP4TextureHit.NONE, MP4TextureHitTest.hit(view,new TexturePoint(180,340)));
    }
    @Test void qualityBackgroundGapConsumesInput() {
        var view = new MP4TextureHitTest.View(false,true,false,false,true, 6, 0);
        assertEquals(MP4TextureHit.NONE, MP4TextureHitTest.hit(view,new TexturePoint(200,119)));
    }

    @Test void emptyPlaylistRowsDoNotSelectTheLastTrack() {
        var view = new MP4TextureHitTest.View(false,true,true,false,false,1,0);
        assertEquals(-1, MP4TextureHitTest.queueRowAt(view,new TexturePoint(100,260)));
        assertEquals(0, MP4TextureHitTest.queueRowAt(view,new TexturePoint(100,140)));
    }
    @Test void scrollingKeepsOnlyExistingRowsInteractive() {
        var view = new MP4TextureHitTest.View(false,true,true,false,false,4,3);
        assertEquals(0, MP4TextureHitTest.queueRowAt(view,new TexturePoint(100,130)));
        assertEquals(-1, MP4TextureHitTest.queueRowAt(view,new TexturePoint(100,160)));
    }

}
