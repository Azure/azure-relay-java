package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

final class CompletableFutureUtil {
	static AtomicInteger tasksRunning = new AtomicInteger(0);

	static CompletableFuture<Void> delayAsync(Duration delay, ScheduledExecutorService executor) {
		if (delay == null || delay.isZero() || delay.isNegative()) {
			return CompletableFuture.completedFuture(null);
		}
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        executor.schedule(() -> {
        	future.complete(null);
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        return future;
	}

	static <T> CompletableFuture<T> fromException(Throwable ex) {
		CompletableFuture<T> future = new CompletableFuture<T>();
		future.completeExceptionally(ex);
		return future;
	}

	static CompletableFuture<Void> timedRunAsync(Duration timeout, Runnable runnable, ScheduledExecutorService executor) {

		return futureToCompletableFuture(timeout, runnable, executor);
	}

	static <T> CompletableFuture<T> timedSupplyAsync(Duration timeout, Supplier<T> supplier, ScheduledExecutorService executor) {
		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				return supplier.get();
			}
		};
		return futureToCompletableFuture(timeout, callable, executor);
	}

	@SuppressWarnings("unchecked")
	private static <T> CompletableFuture<T> futureToCompletableFuture(Duration timeout, Object task, ScheduledExecutorService executor) {
		TimeoutHelper.throwIfNegativeArgument(timeout);
		CompletableFuture<T> taskCF = new CompletableFuture<T>();
		AtomicReference<ScheduledFuture<?>> cancelFuture = new AtomicReference<ScheduledFuture<?>>(null);

		Future<?> taskFuture = executor.submit(() -> {
			T taskResult = null;

			try {
				if (task instanceof Runnable) {
					((Runnable) task).run();
				} else if (task instanceof Callable) {
					taskResult = ((Callable<T>) task).call();
				}
				taskCF.complete(taskResult);
			} catch (Exception e) {
				taskCF.completeExceptionally(e);
			} finally {
				if (cancelFuture.get() != null) {
					cancelFuture.get().cancel(true);
				}
			}
		});

		if (timeout != null) {
			cancelFuture.set(executor.schedule(() -> {
				taskCF.completeExceptionally(
						new TimeoutException("Could not complete CompletableFuture within the timeout duration."));
				taskFuture.cancel(true);
			}, timeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		return taskCF;
	}
	
	static boolean isDoneNormally(CompletableFuture<?> cf) {
		return cf != null && cf.isDone() && !cf.isCancelled() && !cf.isCompletedExceptionally();
	}
}
