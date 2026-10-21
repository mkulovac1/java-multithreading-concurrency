package ba.telco.ocs.common.concurrency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in thread {}", t.getName(), e));
        return thread;
    }
}
