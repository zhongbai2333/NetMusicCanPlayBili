package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoResourceDiagnosticsCollectorTest {
    @Test
    void emptyInstancesStillReportExternalResourceCounters() {
        VideoResourceDiagnosticsCollector<InstanceState> collector = collector();

        VideoResourceDiagnosticsCollector.Snapshot snapshot = collector.collect(List.of(), 2, 3, 4, 5, 6L);

        assertEquals(new VideoResourceDiagnosticsCollector.Snapshot(0, 0, 0, 2, 3, 0, 4, 0, 5, 6L), snapshot);
    }

    @Test
    void aggregatesIndependentInstanceStatesAndReferenceCounts() {
        VideoResourceDiagnosticsCollector<InstanceState> collector = collector();
        List<InstanceState> instances = List.of(
                new InstanceState(true, false, 2, true),
                new InstanceState(false, true, 1, false),
                new InstanceState(true, true, 0, true));

        VideoResourceDiagnosticsCollector.Snapshot snapshot = collector.collect(instances, 7, 8, 9, 10, 11L);

        assertEquals(new VideoResourceDiagnosticsCollector.Snapshot(3, 2, 2, 7, 8, 3, 9, 2, 10, 11L), snapshot);
    }

    @Test
    void collectedSnapshotDoesNotChangeWhenTheSourceCollectionChanges() {
        VideoResourceDiagnosticsCollector<InstanceState> collector = collector();
        ArrayList<InstanceState> instances = new ArrayList<>();
        instances.add(new InstanceState(true, false, 1, false));
        VideoResourceDiagnosticsCollector.Snapshot snapshot = collector.collect(instances, 0, 0, 0, 0, 0L);

        instances.clear();

        assertEquals(1, snapshot.instances());
        assertEquals(1, snapshot.runningInstances());
        assertEquals(1, snapshot.projectorReferences());
    }

    private static VideoResourceDiagnosticsCollector<InstanceState> collector() {
        return new VideoResourceDiagnosticsCollector<>(InstanceState::running, InstanceState::failed,
                InstanceState::projectorReferences, InstanceState::guiConsumer);
    }

    private record InstanceState(boolean running, boolean failed, int projectorReferences, boolean guiConsumer) {
    }
}
