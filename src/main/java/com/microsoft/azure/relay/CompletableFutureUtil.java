package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class CompletableFutureUtil {
	protected static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Math.max(Runtime.getRuntime().availableProcessors(), 4));

	protected static CompletableFuture<Void> timedRunAsync(Duration timeout, Runnable runnable) throws CompletionException {
        initIfNeeded();
        return futureToCompletableFuture(timeout, runnable);
	}
	
	protected static <T> CompletableFuture<T> timedSupplyAsync(Duration timeout, Supplier<T> supplier) throws CompletionException {
		initIfNeeded();
		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				return supplier.get();
			}
		};
		return futureToCompletableFuture(timeout, callable);
	}
	
	@SuppressWarnings("unchecked")
	private static <T> CompletableFuture<T> futureToCompletableFuture(Duration timeout, Object task) throws CompletionException {
		TimeoutHelper.throwIfNegativeArgument(timeout);
		CompletableFuture<T> completableFuture = new CompletableFuture<T>();
		
		// Using supplyAsync here even for Future<Void> because it will just return null and won't cause a problem
		try {
			completableFuture = CompletableFuture.supplyAsync(throwingSupplierWrapper(() -> {
				T result = null;
				ScheduledFuture<?> cancelTask = null;
				
				try {
					Future<?> future = (task instanceof Runnable) ? executor.submit((Runnable) task) : executor.submit((Callable<T>) task);
					if (timeout != null) {
						cancelTask = executor.schedule(() -> future.cancel(true), timeout.toMillis(), TimeUnit.MILLISECONDS);
						result = (T) future.get();
						cancelTask.cancel(true);
					}
					else {
						result = (T) future.get();
					}
				} catch (Exception e) {
					throw (CompletionException) e;
				}
				return result;
			}));
		} catch (Exception e) {
			completableFuture.cancel(true);
			throw (CompletionException) e;
		}
		return completableFuture;
	}

	private static void initIfNeeded() {
        if (executor.isShutdown()) {
        	executor = new ScheduledThreadPoolExecutor(Math.max(Runtime.getRuntime().availableProcessors(), 4));
        }
	}
	
	protected static void cleanup() {
		executor.shutdown();
	}
	
	protected static <T> Supplier<T> throwingSupplierWrapper(ThrowingSupplier<T, CompletionException> throwingSupplier) throws CompletionException {
		return () -> {
			try {
				return throwingSupplier.supply();
			} catch (Exception e) {
				return ThrowingSupplier.throwException(e);
			}
		};
	}
}
