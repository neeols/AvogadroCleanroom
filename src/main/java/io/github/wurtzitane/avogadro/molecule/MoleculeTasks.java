package io.github.wurtzitane.avogadro.molecule;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded CPU preparation. Never executes rejected work on its caller. */
public final class MoleculeTasks {

    /** Atomic integer tracking worker thread creation counts. */
    private static final AtomicInteger THREADS = new AtomicInteger();

    /** Bounded linked blocking deque serving as the task queue for geometry workers. */
    private static final LinkedBlockingDeque<Runnable> QUEUE = new LinkedBlockingDeque<>(32);

    /** Thread pool executor managing background geometry computation workers. */
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, QUEUE,
            action -> {
                Thread thread = new Thread(action, "Avogadro geometry " + THREADS.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    static {
        WORKERS.allowCoreThreadTimeOut(true);
    }

    private MoleculeTasks() {}

    /**
     * Submits a callable task for asynchronous background execution.
     *
     * @param action callable task action to execute
     * @param <T> result value type
     * @return future instance representing pending task results, or {@code null} if rejected
     */
    public static <T> Future<T> submit(Callable<T> action) {
        WORKERS.purge();
        try {
            return WORKERS.submit(action);
        } catch (RejectedExecutionException full) {
            return null;
        }
    }

    /**
     * Submits a callable task with foreground priority, offering it to the front of the execution queue.
     *
     * @param action callable task action to execute
     * @param <T> result value type
     * @return future task instance, or {@code null} if queue is full
     */
    public static <T> Future<T> submitForeground(Callable<T> action) {
        WORKERS.purge();
        FutureTask<T> task = new FutureTask<>(action);
        if (!QUEUE.offerFirst(task))
            return null;
        WORKERS.prestartAllCoreThreads();
        return task;
    }

    /**
     * Cancellation is linked to the FutureTask, including a task still in the queue.
     *
     * @param action callable task action to execute
     * @param <T> result value type
     * @return completable future resolving to the task result
     */
    public static <T> CompletableFuture<T> async(Callable<T> action) {
        class Result extends CompletableFuture<T> {
            FutureTask<T> task;
            @Override
            public boolean cancel(boolean interrupt) {
                if (!super.cancel(interrupt))
                    return false;
                task.cancel(interrupt);
                WORKERS.remove(task);
                return true;
            }
        }
        Result result = new Result();
        result.task = new FutureTask<>(action) {
            @Override
            protected void done() {
                if (isCancelled()) {
                    result.cancel(false);
                    return;
                }
                try {
                    result.complete(get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(interrupted);
                } catch (ExecutionException failure) {
                    result.completeExceptionally(failure.getCause());
                }
            }
        };
        WORKERS.purge();
        try {
            WORKERS.execute(result.task);
        } catch (RejectedExecutionException full) {
            result.completeExceptionally(full);
        }
        return result;
    }

    /**
     * Checks whether the current thread has been interrupted and throws a cancellation exception if so.
     *
     * @throws CancellationException if the current thread is interrupted
     */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Molecule preparation cancelled");
    }
}
