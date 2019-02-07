package com.microsoft.azure.relay;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class AutoShutdownScheduledExecutor implements ScheduledExecutorService {
	public static final AutoShutdownScheduledExecutor EXECUTOR = new AutoShutdownScheduledExecutor(Math.max(Runtime.getRuntime().availableProcessors(), 4));
	private final Object thisLock = new Object();
	private final int corePoolSize;
	private int refCount = 0;
	private ScheduledThreadPoolExecutor innerExecutor;

	private AutoShutdownScheduledExecutor(int size) {
		corePoolSize = size;
	}

	@Override
	public <T> Future<T> submit(Callable<T> task) {
		this.incrementRefCount();

		Callable<T> callable = new Callable<T>() {
			@Override
			public T call() throws Exception {
				try {
					return task.call();
				} finally {
					AutoShutdownScheduledExecutor.this.decrementRefCount();
				}
			}
		};

		return innerExecutor.submit(callable);
	}

	@Override
	public Future<?> submit(Runnable task) {
		this.incrementRefCount();
		return innerExecutor.submit(() -> {
			try {
				task.run();
			} finally {
				this.decrementRefCount();
			}
		});
	}

	@Override
	public <T> Future<T> submit(Runnable task, T result) {
		this.incrementRefCount();
		return innerExecutor.submit(() -> {
			try {
				task.run();
			} finally {
				this.decrementRefCount();
			}
		}, result);
	}

	@Override
	public void execute(Runnable command) {
		this.incrementRefCount();
		innerExecutor.execute(() -> {
			try {
				command.run();
			} finally {
				this.decrementRefCount();
			}
		});
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		this.incrementRefCount();
		Runnable wrapper = () -> {
			try {
				command.run();
			} finally {
				this.decrementRefCount();
			}
		};

		return this.wrapFuture(innerExecutor.schedule(wrapper, delay, unit));
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		this.incrementRefCount();

		Callable<V> wrapper = new Callable<V>() {
			@Override
			public V call() throws Exception {
				try {
					return callable.call();
				} finally {
					AutoShutdownScheduledExecutor.this.decrementRefCount();
				}
			}
		};

		return this.wrapFuture(innerExecutor.schedule(wrapper, delay, unit));
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
			throws InterruptedException, ExecutionException {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isShutdown() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isTerminated() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void shutdown() {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Runnable> shutdownNow() {
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		throw new UnsupportedOperationException();
	}

	private void incrementRefCount() {
		synchronized (this.thisLock) {
			refCount++;
			if (refCount == 1) {
				innerExecutor = new ScheduledThreadPoolExecutor(corePoolSize);
			}
		}
	}

	void decrementRefCount() {
		ScheduledExecutorService executorToShutdown = null;
		synchronized (this.thisLock) {
			refCount--;
			if (refCount == 0) {
				executorToShutdown = innerExecutor;
				innerExecutor = null;
			}
		}

		if (executorToShutdown != null) {
			executorToShutdown.shutdown();
		}
	}

	private <T> ScheduledFuture<T> wrapFuture(ScheduledFuture<T> schedule) {
		return new CancellableScheduledFuture<T>(schedule);
	}

	private class CancellableScheduledFuture<T> implements ScheduledFuture<T> {

		private final ScheduledFuture<T> innerFuture;

		public CancellableScheduledFuture(ScheduledFuture<T> innerFuture) {
			this.innerFuture = innerFuture;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return this.innerFuture.getDelay(unit);
		}

		@Override
		public int compareTo(Delayed o) {
			return this.innerFuture.compareTo(o);
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			boolean cancelResult = this.innerFuture.cancel(mayInterruptIfRunning);
			if (cancelResult) {
				AutoShutdownScheduledExecutor.this.decrementRefCount();
			}

			return cancelResult;
		}

		@Override
		public T get() throws InterruptedException, ExecutionException {
			return this.innerFuture.get();
		}

		@Override
		public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			return this.innerFuture.get(timeout, unit);
		}

		@Override
		public boolean isCancelled() {
			return this.innerFuture.isCancelled();
		}

		@Override
		public boolean isDone() {
			return this.innerFuture.isDone();
		}
	}
}
