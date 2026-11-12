package com.zhongbai233.net_music_can_play_bili.server;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import static org.junit.jupiter.api.Assertions.*;
class AsyncCanonicalResourceCacheTest {
    @Test void coldRequestsSingleFlightAndConsumerCancellationDoesNotCancelPeer() {
        List<Runnable> tasks=new ArrayList<>();AtomicLong time=new AtomicLong();AtomicInteger calls=new AtomicInteger();
        var c=new AsyncCanonicalResourceCache<String>(2,100,10,time::get,tasks::add,k->{calls.incrementAndGet();return Optional.of("BV");});
        var a=c.resolve("b23");var b=c.resolve("b23");assertFalse(c.ready("b23"));assertTrue(c.peek("b23").isEmpty());assertEquals(1,tasks.size());a.cancel(true);tasks.getFirst().run();assertEquals(Optional.of("BV"),b.join());assertEquals(1,calls.get());assertTrue(c.ready("b23"));assertEquals(Optional.of("BV"),c.resolve("b23").join());
    }
    @Test void successAndFailureHaveDifferentTtlAndLoaderNeverHoldsCacheLock() throws Exception {
        AtomicLong time=new AtomicLong();AtomicInteger calls=new AtomicInteger();var c=new AsyncCanonicalResourceCache<String>(2,100,10,time::get,Runnable::run,k->{calls.incrementAndGet();return k.equals("good")?Optional.of("BV"):Optional.empty();});
        assertTrue(c.resolve("bad").join().isEmpty());time.set(9);c.resolve("bad").join();assertEquals(1,calls.get());time.set(10);c.resolve("bad").join();assertEquals(2,calls.get());
        c.resolve("good").join();time.set(109);assertTrue(c.ready("good"));time.set(110);assertFalse(c.ready("good"));
    }
    @Test void saturationAndRejectedExecutorFailClosedWithoutUnboundedTasks() {
        List<Runnable>tasks=new ArrayList<>();var c=new AsyncCanonicalResourceCache<String>(1,100,10,()->0L,tasks::add,k->Optional.of(k));
        c.resolve("a");assertTrue(c.resolve("b").join().isEmpty());assertEquals(1,tasks.size());
        var reject=new AsyncCanonicalResourceCache<String>(1,100,10,()->0L,r->{throw new RejectedExecutionException();},k->Optional.of(k));assertTrue(reject.resolve("x").join().isEmpty());assertTrue(reject.ready("x"));
    }
    @Test void clearDoesNotReinstallAnOldInFlightCanonicalResult() {
        List<Runnable>tasks=new ArrayList<>();AtomicInteger n=new AtomicInteger();var c=new AsyncCanonicalResourceCache<String>(2,100,10,()->0L,tasks::add,k->Optional.of("BV"+n.incrementAndGet()));
        var old=c.resolve("x");c.clear();var fresh=c.resolve("x");tasks.get(0).run();assertTrue(c.peek("x").isEmpty());tasks.get(1).run();assertEquals(Optional.of("BV1"),fresh.join());assertEquals(Optional.of("BV1"),c.peek("x"));assertEquals(Optional.empty(),old.join());assertEquals(1,n.get());
    }
    @Test void loaderCanReenterAndCompletionCanRequestAnotherIdentity() {
        var ref=new AtomicReference<AsyncCanonicalResourceCache<String>>();var c=new AsyncCanonicalResourceCache<String>(3,100,10,()->0L,Runnable::run,k->{assertFalse(Thread.holdsLock(ref.get()));return Optional.of(k);});ref.set(c);
        assertEquals(Optional.of("y"),c.resolve("x").thenCompose(v->c.resolve("y")).join());
    }
}
