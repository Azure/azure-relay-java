package com.microsoft.azure.relay;

import java.lang.reflect.Array;
import java.time.Duration;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class InputQueue<T> {
	private final ScheduledExecutorService executor;
	private final ItemQueue itemQueue;
	private final Queue<CompletableFuture<T>> readerQueue;

	private QueueState queueState;
	private final Object thisLock = new Object();

	public int getPendingCount() {
		synchronized (thisLock) {
			return this.itemQueue.getTotalCount();
		}
	}

	public int getReadersQueueCount() {
		synchronized (thisLock) {
			return this.readerQueue.size();
		}
	}

	public InputQueue(ScheduledExecutorService executor) {
		this.executor = executor;		
		this.itemQueue = new ItemQueue();
		this.readerQueue = new LinkedList<CompletableFuture<T>>();
		this.queueState = QueueState.OPEN;
	}

	public CompletableFuture<T> dequeueAsync() {
		return dequeueAsync(null);
	}
	
	public CompletableFuture<T> dequeueAsync(Duration timeout) {
		Item item = null;

		synchronized (thisLock) {
			if (this.queueState == QueueState.OPEN) {
				if (itemQueue.hasAvailableItem()) {
					item = itemQueue.dequeueAvailableItem();
				} else {
					return this.createReader(timeout);
				}
			} else if (queueState == QueueState.SHUTDOWN) {
				if (itemQueue.hasAvailableItem()) {
					item = itemQueue.dequeueAvailableItem();
				} else if (itemQueue.hasAnyItem()) {
					return this.createReader(timeout);
				}
			}
		}

		invokeDequeuedCallback(item);
		CompletableFuture<T> future = new CompletableFuture<T>();
		if (item != null && item.getException()!= null) {
			future.completeExceptionally(item.getException());
		} else {
			future.complete((item != null) ? item.getValue() : null);
		}
		return future;
	}
	
	private CompletableFuture<T> createReader(Duration timeout) {
		CompletableFuture<T> reader = new CompletableFuture<T>();

		if (timeout != null) {
			Future<?> cancelTask = executor.schedule(() -> {
				if (this.removeReader(reader)) {
					reader.completeExceptionally(new TimeoutException("This InputQueue item could not complete in time."));
				}
			}, timeout.toMillis(), TimeUnit.MILLISECONDS);

			reader.thenRunAsync(() -> cancelTask.cancel(true));
		}

		this.readerQueue.add(reader);
		return reader;
	}

	@SuppressWarnings("unchecked")
	public void dispatch() {
		CompletableFuture<T> reader = null;
		CompletableFuture<T>[] outstandingReaders = null;
		Item item = new Item();

		synchronized (thisLock) {
			if (this.queueState != QueueState.CLOSED) {
				this.itemQueue.makePendingItemAvailable();

				if (this.readerQueue.size() > 0) {
					item = this.itemQueue.dequeueAvailableItem();
					reader = this.readerQueue.remove();

					if (this.queueState == QueueState.SHUTDOWN && this.readerQueue.size() > 0 && itemQueue.getTotalCount() == 0) {
						outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class, this.readerQueue.size());

						// manually copy over values because only cloning of primitives are allowed
						int i = 0;
						while (!this.readerQueue.isEmpty()) {
							outstandingReaders[i++] = this.readerQueue.remove();
						}
					}
				}
			}
		}

		if (outstandingReaders != null) {
			CompletableFuture<T>[] outstandingReadersRef = outstandingReaders;
			executor.submit(() -> completeOutstandingReadersCallback(outstandingReadersRef));
		}

		if (reader != null) {
			invokeDequeuedCallback(item);
			reader.complete(item.getValue());
		}
	}

	public void enqueueAndDispatch(T item) {
		enqueueAndDispatch(item, null);
	}

	// dequeuedCallback is called as an item is dequeued from the InputQueue. The
	// InputQueue lock is not held during the callback. However, the user code will
	// not be notified of the item being available until the callback returns. If
	// you are not sure if the callback will block for a long time, then first call
	// ActionItem.Schedule to get to a "safe" thread.
	public void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback) {
		enqueueAndDispatch(item, dequeuedCallback, true);
	}

	public void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback, boolean canDispatchOnThisThread) {
		enqueueAndDispatch(new Item(item, dequeuedCallback), canDispatchOnThisThread);
	}

	public boolean enqueueWithoutDispatch(T item, Consumer<T> dequeuedCallback) {
		return enqueueWithoutDispatch(new Item(item, dequeuedCallback));
	}

	public boolean enqueueWithoutDispatch(Exception exception, Consumer<T> dequeuedCallback) {
		return enqueueWithoutDispatch(new Item(exception, dequeuedCallback));
	}

	public void shutdown() {
		this.shutdown(null);
	}

	// Don't let any more items in. Differs from Close in that we keep around
	// existing items in our itemQueue for possible future calls to Dequeue
	@SuppressWarnings("unchecked")
	public void shutdown(Supplier<Exception> pendingExceptionGenerator) {
		CompletableFuture<T>[] outstandingReaders = null;

		synchronized (thisLock) {

			if (queueState == QueueState.SHUTDOWN || queueState == QueueState.CLOSED) {
				return;
			}

			this.queueState = QueueState.SHUTDOWN;
			if (this.readerQueue.size() > 0 && this.itemQueue.getTotalCount() == 0) {
				outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class, readerQueue.size());

				// manually copy over values because only cloning of primitives are allowed
				int i = 0;
				while (!this.readerQueue.isEmpty()) {
					outstandingReaders[i++] = this.readerQueue.remove();
				}
			}
		}

		if (outstandingReaders != null) {
			for (int i = 0; i < outstandingReaders.length; i++) {
				Exception exception = (pendingExceptionGenerator != null) ? pendingExceptionGenerator.get() : null;
				if (exception == null) {
					outstandingReaders[i].complete(null);
				} else {
					outstandingReaders[i].completeExceptionally(exception);
				}
			}
		}
	}

	public void dispose() {
		boolean dispose = false;

		synchronized (thisLock) {
			if (queueState != QueueState.CLOSED) {
				queueState = QueueState.CLOSED;
				dispose = true;
			}
		}

		if (dispose) {
			while (this.readerQueue.size() > 0) {
				CompletableFuture<T> reader = this.readerQueue.remove();
				reader.complete(null);
			}

			while (itemQueue.hasAnyItem()) {
				Item item = itemQueue.dequeueAnyItem();
				invokeDequeuedCallback(item);
			}
		}
	}

	void completeOutstandingReadersCallback(CompletableFuture<T>[] outstandingReaders) {
		for (int i = 0; i < outstandingReaders.length; i++) {
			outstandingReaders[i].complete(null);
		}
	}

	void invokeDequeuedCallback(Item item) {
		if (item != null && item.getDequeuedCallback() != null) {
			item.dequeuedCallback.accept(item.getValueWithException());
		}
	}

	void invokeDequeuedCallbackLater(Item item) {
		if (item != null && item.getDequeuedCallback() != null) {
			executor.submit(() -> onInvokeDequeuedCallback(item));
		}
	}

	@SuppressWarnings("unchecked")
	void onDispatchCallback(Object state) {
		((InputQueue<T>) state).dispatch();
	}

	@SuppressWarnings("unchecked")
	void onInvokeDequeuedCallback(Object state) {
		Item item = (Item) state;
		item.getDequeuedCallback().accept(item.getValueWithException());
	}

	void enqueueAndDispatch(Item item, boolean canDispatchOnThisThread) {
		boolean disposeItem = false;
		CompletableFuture<T> reader = null;
		boolean dispatchLater = false;

		synchronized (thisLock) {
			if (queueState == QueueState.OPEN) {
				if (canDispatchOnThisThread) {
					if (this.readerQueue.size() == 0) {
						itemQueue.enqueueAvailableItem(item);
					} else {
						reader = this.readerQueue.remove();
					}
				} else {
					if (this.readerQueue.size() == 0) {
						itemQueue.enqueueAvailableItem(item);
					} else {
						itemQueue.enqueuePendingItem(item);
						dispatchLater = true;
					}
				}
			} else {
				disposeItem = true;
			}
		}

		if (reader != null) {
			invokeDequeuedCallback(item);
			reader.complete(item.getValue());
		}

		if (dispatchLater) {
			executor.submit(() -> onDispatchCallback(this));
		} else if (disposeItem) {
			invokeDequeuedCallback(item);
		}
	}

	// This will not block, however, Dispatch() must be called later if this
	// function returns true.
	boolean enqueueWithoutDispatch(Item item) {
		synchronized (thisLock) {
			if (queueState != QueueState.CLOSED && queueState != QueueState.SHUTDOWN) {

				if (this.readerQueue.size() == 0) {
					itemQueue.enqueueAvailableItem(item);
					return false;
				} else {
					itemQueue.enqueuePendingItem(item);
					return true;
				}
			}
		}

		invokeDequeuedCallbackLater(item);
		return false;
	}

	// Used for timeouts. The InputQueue must remove readers from its reader queue
	// to prevent dispatching items to timed out readers.
	boolean removeReader(CompletableFuture<T> reader) {
		synchronized (thisLock) {
			if (queueState == QueueState.OPEN || queueState == QueueState.SHUTDOWN) {
				return readerQueue.remove(reader);
			}
		}
		return false;
	}

	private enum QueueState {
		OPEN, SHUTDOWN, CLOSED
	}

	private class Item {
		private Consumer<T> dequeuedCallback;
		private Exception exception;
		private T value;

		// Simulate empty struct constructor in C#
		Item() {
			this(null, null, null);
		}

		Item(T value, Consumer<T> dequeuedCallback) {
			this(value, null, dequeuedCallback);
		}

		Item(Exception exception, Consumer<T> dequeuedCallback) {
			this(null, exception, dequeuedCallback);
		}

		Item(T value, Exception exception, Consumer<T> dequeuedCallback) {
			this.value = value;
			this.exception = exception;
			this.dequeuedCallback = dequeuedCallback;
		}

		Consumer<T> getDequeuedCallback() {
			return this.dequeuedCallback;
		}

		Exception getException() {
			return this.exception;
		}

		T getValue() {
			return this.value;
		}

		T getValueWithException() {
			if (this.exception != null) {
				throw RelayLogger.throwingException(this.exception, this, TraceLevel.WARNING);
			}

			return this.value;
		}
	}

	private class ItemQueue {
		private int head;
		private Item[] items;
		private int pendingCount;
		private int totalCount;

		@SuppressWarnings("unchecked")
		ItemQueue() {
			this.items = (Item[]) Array.newInstance(Item.class, 1);
		}

		// same as ItemCount
		int getTotalCount() {
			return this.totalCount;
		}

		boolean hasAnyItem() {
			return this.totalCount > 0;
		}

		boolean hasAvailableItem() {
			return this.totalCount > this.pendingCount;
		}

		Item dequeueAnyItem() {
			if (this.pendingCount == this.totalCount) {
				this.pendingCount--;
			}

			return dequeueItemCore();
		}

		Item dequeueAvailableItem() {
			if (this.totalCount == this.pendingCount) {
				throw new RuntimeException("ItemQueue does not contain any available items");
			}

			return dequeueItemCore();
		}

		void enqueueAvailableItem(Item item) {
			enqueueItemCore(item);
		}

		void enqueuePendingItem(Item item) {
			enqueueItemCore(item);
			this.pendingCount++;
		}

		void makePendingItemAvailable() {
            if (pendingCount == 0) {
            	throw RelayLogger.invalidOperation("ItemQueue does not contain any pending items", this);
            }

			this.pendingCount--;
		}

		Item dequeueItemCore() {
            if (totalCount == 0) {
            	throw RelayLogger.invalidOperation("ItemQueue does not contain any items", this);
            }

			Item item = this.items[this.head];
			this.items[this.head] = new Item();
			this.totalCount--;
			this.head = (this.head + 1) % this.items.length;
			return item;
		}

		@SuppressWarnings("unchecked")
		void enqueueItemCore(Item item) {

			if (this.totalCount == this.items.length) {
				Item[] newItems = (Item[]) Array.newInstance(Item.class, this.items.length * 2);
				
				for (int i = 0; i < this.totalCount; i++) {
					newItems[i] = this.items[(head + i) % this.items.length];
				}
				this.head = 0;
				this.items = newItems;
			}

			int tail = (this.head + this.totalCount) % this.items.length;
			this.items[tail] = item;
			this.totalCount++;
		}
	}
}
