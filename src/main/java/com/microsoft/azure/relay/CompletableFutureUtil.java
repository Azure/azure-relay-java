package com.microsoft.azure.relay;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import javax.websocket.Session;

public final class CompletableFutureUtil {
	protected static ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Math.max(Runtime.getRuntime().availableProcessors(), 4));

	protected static CompletableFuture<Void> timedRunAsync(Duration timeout, Runnable runnable) throws TimeoutException {
        initIfNeeded();
		return futureToCompletableFuture(timeout, executor.submit(runnable));
	}
	
	protected static <T> CompletableFuture<T> timedSupplyAsync(Duration timeout, Supplier<T> supplier) throws TimeoutException {
		initIfNeeded();
		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				return supplier.get();
			}
		};
		return futureToCompletableFuture(timeout, executor.submit(callable));
	}
	
	private static <T> CompletableFuture<T> futureToCompletableFuture(Duration timeout, Future<?> future) throws TimeoutException {
		TimeoutHelper.throwIfNegativeArgument(timeout);
		CompletableFuture<T> completableFuture = new CompletableFuture<T>();
		
		// Using supplyAsync here even for Future<Void> because it will just return null and won't cause a problem
		try {
			completableFuture = CompletableFuture.supplyAsync(throwingSupplierWrapper(() -> {
				T result = null;
				try {
					result = (timeout == null) ? (T) future.get() : (T) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				} catch (InterruptedException | ExecutionException e) {
					// TODO: trace
					e.printStackTrace();
				}
				return result;
			}));
		} catch (Exception e) {
			if (e instanceof TimeoutException) {
				throw (TimeoutException) e;
			}
			e.printStackTrace();
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
	
	protected static <T> Supplier<T> throwingSupplierWrapper(ThrowingSupplier<T, Exception> throwingSupplier) throws Exception {
		return () -> {
			try {
				return throwingSupplier.supply();
			} catch (Exception e) {
				return ThrowingSupplier.throwException(e);
			}
		};
	}
}
