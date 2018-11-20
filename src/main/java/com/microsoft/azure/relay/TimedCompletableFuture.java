package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.websocket.Session;

public final class TimedCompletableFuture {
	private static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Math.max(Runtime.getRuntime().availableProcessors(), 4));

	public static CompletableFuture<Void> timedRunAsync(Duration timeout, Runnable runnable) {
		return futureToCompletableFuture(timeout, executor.submit(runnable));
	}
	
	public static <T> CompletableFuture<T> timedSupplyAsync(Duration timeout, Supplier<T> supplier) {
		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				return supplier.get();
			}
		};
		return futureToCompletableFuture(timeout, executor.submit(callable));
	}
	
	
	
	@SuppressWarnings("unchecked")
	public static <T> CompletableFuture<T> futureToCompletableFuture(Duration timeout, Future<?> future) {
		TimeoutHelper.throwIfNegativeArgument(timeout);
		
		// Using supplyAsunc here even for Future<Void> because it will just return null and won't cause a problem
		return CompletableFuture.supplyAsync(() -> {
			T result = null;
			try {
				result = (timeout == null) ? (T) future.get() : (T) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				// TODO: trace
				e.printStackTrace();
			}
			return result;
		});
	}

	private static void cancelIfTimeout(Future<?> future, Duration timeout) {
		executor.schedule(new Runnable() {
			@Override
			public void run() {
				if (!future.isDone()) {
					future.cancel(true);
				}
			}
		}, timeout.toMillis(), TimeUnit.MILLISECONDS);
	}
}
