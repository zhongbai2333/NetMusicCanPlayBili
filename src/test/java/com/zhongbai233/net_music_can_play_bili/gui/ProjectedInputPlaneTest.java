package com.zhongbai233.net_music_can_play_bili.gui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProjectedInputPlaneTest {
    // Independent pinhole camera reference, with depth varying along both texture axes.
    private static double[] project(double u, double v, double scale) {
        double x = (u - .5) * .57142857, y = (.5 - v);
        double z = 1.3 + x * Math.sin(Math.toRadians(4)) + y * Math.sin(Math.toRadians(-7));
        return new double[] { scale * (2 + x / z), scale * (3 - y / z) };
    }
    @Test void perspectiveInteriorRoundTripsAcrossGuiScales() {
        for (double scale : new double[]{80, 160, 320, 640}) {
            var a = project(0,0,scale); var b = project(1,0,scale);
            var c = project(1,1,scale); var d = project(0,1,scale);
            var plane = new ProjectedInputPlane(a[0],a[1],b[0],b[1],c[0],c[1],d[0],d[1]);
            for (double u : new double[]{.01,.2,.5,.8,.99}) for (double v : new double[]{.02,.25,.5,.75,.98}) {
                var screen = project(u,v,scale);
                var point = plane.toTexturePoint(screen[0],screen[1],256,448);
                assertNotNull(point);
                assertEquals(u*256,point.x(),1e-7);
                assertEquals(v*448,point.y(),1e-7);
            }
        }
    }
    @Test void fractionalMouseCoordinatesArePreserved() {
        var point = new ProjectedInputPlane(0,0,256,0,256,448,0,448).toTexturePoint(100.99,200.99,256,448);
        assertEquals(100.99,point.x(),1e-8);
        assertEquals(200.99,point.y(),1e-8);
    }
    @Test void rotatedPlanePreservesEdgeCoordinatesAndPivot() {
        var plane = new ProjectedInputPlane(448,0,448,256,0,256,0,0);
        var pivot = plane.sample(.3,.7);
        var point = plane.toTexturePoint(pivot.x(),pivot.y(),256,448);
        assertEquals(.3*256,point.x(),1e-8);
        assertEquals(.7*448,point.y(),1e-8);
    }
    @Test void outsideAndNonFiniteCoordinatesAreRejected() {
        var plane = new ProjectedInputPlane(0,0,256,0,256,448,0,448);
        assertNull(plane.toTexturePoint(-2,224,256,448));
        assertNull(plane.toTexturePoint(Double.NaN,224,256,448));
    }
    @Test void collapsedPlaneIsRejected() {
        assertNull(new ProjectedInputPlane(0,0,0,0,0,0,0,0).toTexturePoint(0,0,256,448));
    }
}
