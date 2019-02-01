package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class CompletableFutureUtil {
	protected static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());
	static AtomicInteger tasksRunning = new AtomicInteger(0);

    static CompletableFuture<Void> delayAsync(Duration delay) {
        prepareNewTask();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return CompletableFuture.completedFuture(null);
        }
       
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        executor.schedule(() -> future.complete(null), delay.toMillis(), TimeUnit.MILLISECONDS);
        return future;
    }
    
    static <T> CompletableFuture<T> fromException(Throwable ex) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(ex);
        return future;
    }
    
	static CompletableFuture<Void> timedRunAsync(Duration timeout, Runnable runnable) {
		prepareNewTask();
		return futureToCompletableFuture(timeout, runnable);
	}

	static <T> CompletableFuture<T> timedSupplyAsync(Duration timeout, Supplier<T> supplier) {
		prepareNewTask();
		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				return supplier.get();
			}
		};
		return futureToCompletableFuture(timeout, callable);
	}

	@SuppressWarnings("unchecked")
	private static <T> CompletableFuture<T> futureToCompletableFuture(Duration timeout, Object task) {
		
		TimeoutHelper.throwIfNegativeArgument(timeout);
		CompletableFuture<T> taskCF = new CompletableFuture<T>();
		AtomicReference<ScheduledFuture<?>> cancelFuture = new AtomicReference<ScheduledFuture<?>>(null);
		
		Future<?> taskFuture = executor.submit(() -> {
			T taskResult = null;
			
			try {
				if (task instanceof Runnable) {
					((Runnable) task).run();
				}
				else if (task instanceof Callable) {
					taskResult = ((Callable<T>) task).call();
				}
				taskCF.complete(taskResult);
			} 
			catch (Exception e) {
				taskCF.completeExceptionally(e);
			}
			finally {
				if (cancelFuture.get() != null) {
					cancelFuture.get().cancel(true);
				}
			}
		});
		
		if (timeout != null) {
			cancelFuture.set(executor.schedule(() -> {
				taskFuture.cancel(true);
				taskCF.completeExceptionally(new TimeoutException("Could not complete CompletableFuture within the timeout duration."));
			}, timeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		
		return taskCF;
	}

	private static void prepareNewTask() {
		initExecutorIfNeeded();
		tasksRunning.incrementAndGet();
	}
	
	private static void initExecutorIfNeeded() {
		if (executor.isShutdown() || executor.isTerminating() || executor.isTerminated()) {
			executor = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());
		}
	}
}
