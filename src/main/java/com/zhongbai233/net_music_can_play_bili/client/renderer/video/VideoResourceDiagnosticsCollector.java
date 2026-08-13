package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 聚合视频实例引用与外部 pending/BER/zombie 资源计数。 */
final class VideoResourceDiagnosticsCollector<T> {
    private final Predicate<? super T> runningProbe;
    private final Predicate<? super T> failureProbe;
    private final ToIntFunction<? super T> projectorReferencesProbe;
    private final Predicate<? super T> guiConsumerProbe;

    VideoResourceDiagnosticsCollector(Predicate<? super T> runningProbe, Predicate<? super T> failureProbe,
            ToIntFunction<? super T> projectorReferencesProbe, Predicate<? super T> guiConsumerProbe) {
        this.runningProbe = Objects.requireNonNull(runningProbe, "runningProbe");
        this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe");
        this.projectorReferencesProbe = Objects.requireNonNull(projectorReferencesProbe,
                "projectorReferencesProbe");
        this.guiConsumerProbe = Objects.requireNonNull(guiConsumerProbe, "guiConsumerProbe");
    }

    Snapshot collect(Collection<? extends T> instances, int pendingLoading, int pendingFailure,
            int berManagedProjectors, int activeCloseZombies, long lateCloseConvergences) {
        int instanceCount = 0;
        int runningInstances = 0;
        int failedInstances = 0;
        int projectorReferences = 0;
        int guiConsumers = 0;
        if (instances != null) {
            for (T instance : instances) {
                if (instance == null) {
                    continue;
                }
                instanceCount++;
                if (runningProbe.test(instance)) {
                    runningInstances++;
                }
                if (failureProbe.test(instance)) {
                    failedInstances++;
                }
                projectorReferences += projectorReferencesProbe.applyAsInt(instance);
                if (guiConsumerProbe.test(instance)) {
                    guiConsumers++;
                }
            }
        }
        return new Snapshot(instanceCount, runningInstances, failedInstances, pendingLoading, pendingFailure,
                projectorReferences, berManagedProjectors, guiConsumers, activeCloseZombies,
                lateCloseConvergences);
    }

    record Snapshot(int instances, int runningInstances, int failedInstances, int pendingLoading,
            int pendingFailure, int projectorReferences, int berManagedProjectors, int guiConsumers,
            int activeCloseZombies, long lateCloseConvergences) {
    }
}
