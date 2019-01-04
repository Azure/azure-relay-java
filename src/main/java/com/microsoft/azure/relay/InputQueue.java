package com.microsoft.azure.relay;

import java.lang.reflect.Array;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class InputQueue<T extends Object> {
	private final ItemQueue itemQueue;
	private final Queue<CompletableFuture<T>> readerQueue;
	private final List<CompletableFuture<T>> waiterList;

	private QueueState queueState;
	private Object thisLock = new Object();

	private int pendingCount;
	private Consumer<T> disposeItemCallback;

	protected int getPendingCount() {
		synchronized (thisLock) {
			return this.pendingCount;
		}
	}

	protected int getReadersQueueCount() {
		synchronized (thisLock) {
			return this.readerQueue.size();
		}
	}

	protected Consumer<T> getDisposeItemCallback() {
		return this.disposeItemCallback;
	}

	protected void setDisposeItemCallback(Consumer<T> callback) {
		this.disposeItemCallback = callback;
	}

	protected InputQueue() {
		this.itemQueue = new ItemQueue();
		this.readerQueue = new LinkedList<CompletableFuture<T>>();
		this.waiterList = new LinkedList<CompletableFuture<T>>();
		this.queueState = QueueState.OPEN;
	}

	protected CompletableFuture<T> dequeueAsync() {
		return this.dequeueAsync(null);
	}

	protected CompletableFuture<T> dequeueAsync(Object state) {
		Item item = null;

		synchronized (thisLock) {

			if (this.queueState == QueueState.OPEN) {

				if (itemQueue.hasAvailableItem()) {
					item = itemQueue.dequeueAvailableItem();
				} else {
					CompletableFuture<T> reader = new CompletableFuture<T>();
					this.readerQueue.add(reader);
					return reader;
				}
			} else if (queueState == QueueState.SHUTDOWN) {

				if (itemQueue.hasAvailableItem()) {
					item = itemQueue.dequeueAvailableItem();
				} else if (itemQueue.hasAnyItem()) {
					CompletableFuture<T> reader = new CompletableFuture<T>();
					this.readerQueue.add(reader);
					return reader;
				}
			}
		}

		invokeDequeuedCallback(item);
		return (item != null) ? CompletableFuture.completedFuture(item.getValue())
				: CompletableFuture.completedFuture(null);
	}

	protected CompletableFuture<Boolean> waitForItemAsync() {
		return this.waitForItemAsync(null);
	}

	@SuppressWarnings("unchecked")
	protected CompletableFuture<Boolean> waitForItemAsync(Object state) {

		synchronized (thisLock) {
			if (queueState == QueueState.OPEN) {
				if (!itemQueue.hasAvailableItem()) {
					CompletableFuture<T> waiter = new CompletableFuture<T>();
					waiterList.add(waiter);
					return (CompletableFuture<Boolean>) waiter;
				}
			} else if (queueState == QueueState.SHUTDOWN) {
				if (!itemQueue.hasAvailableItem() && itemQueue.hasAnyItem()) {
					CompletableFuture<T> waiter = new CompletableFuture<T>();
					waiterList.add(waiter);
					return (CompletableFuture<Boolean>) waiter;
				}
			}
		}

		return CompletableFuture.completedFuture(true);
	}

	protected void close() {
		dispose();
	}

	@SuppressWarnings("unchecked")
	protected void dispatch() {
		CompletableFuture<T> reader = null;
		CompletableFuture<T>[] outstandingReaders = null;
		CompletableFuture<Boolean>[] waiters = null;
		Item item = new Item();
		boolean itemAvailable = true;

		synchronized (thisLock) {

			itemAvailable = !((queueState == QueueState.CLOSED) || (queueState == QueueState.SHUTDOWN));
			waiters = this.getWaiters();

			if (queueState != QueueState.CLOSED) {
				this.itemQueue.makePendingItemAvailable();

				if (this.readerQueue.size() > 0) {
					item = this.itemQueue.dequeueAvailableItem();
					reader = this.readerQueue.remove();

					if (queueState == QueueState.SHUTDOWN && readerQueue.size() > 0 && itemQueue.getTotalCount() == 0) {
						outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class,
								readerQueue.size());

						// manually copy over values because only cloning of primitives are allowed
						int i = 0;
						while (!readerQueue.isEmpty()) {
							outstandingReaders[i++] = readerQueue.remove();
						}

						itemAvailable = false;
					}
				}
			}
		}

		if (outstandingReaders != null) {
			ActionItem.schedule(s -> completeOutstandingReadersCallback(s), outstandingReaders);
		}

		if (waiters != null) {
			completeWaitersLater(itemAvailable, waiters);
		}

		if (reader != null) {
			invokeDequeuedCallback(item);
			reader.complete((T) item.getValue());
		}
	}

	protected void enqueueAndDispatch(T item) {
		enqueueAndDispatch(item, null);
	}

	// dequeuedCallback is called as an item is dequeued from the InputQueue. The
	// InputQueue lock is not held during the callback. However, the user code will
	// not be notified of the item being available until the callback returns. If
	// you
	// are not sure if the callback will block for a long time, then first call
	// ActionItem.Schedule to get to a "safe" thread.
	protected void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback) {
		enqueueAndDispatch(item, dequeuedCallback, true);
	}

	protected void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback, boolean canDispatchOnThisThread) {
		enqueueAndDispatch(new Item(item, dequeuedCallback), canDispatchOnThisThread);
	}

	protected boolean enqueueWithoutDispatch(T item, Consumer<T> dequeuedCallback) {
		return enqueueWithoutDispatch(new Item(item, dequeuedCallback));
	}

	protected boolean enqueueWithoutDispatch(Exception exception, Consumer<T> dequeuedCallback) {
		return enqueueWithoutDispatch(new Item(exception, dequeuedCallback));
	}

	protected void shutdown() {
		this.shutdown(null);
	}

	// Don't let any more items in. Differs from Close in that we keep around
	// existing items in our itemQueue for possible future calls to Dequeue
	@SuppressWarnings("unchecked")
	protected void shutdown(Supplier<Exception> pendingExceptionGenerator) {
		CompletableFuture<T>[] outstandingReaders = null;

		synchronized (thisLock) {

			if (queueState == QueueState.SHUTDOWN || queueState == QueueState.CLOSED) {
				return;
			}

			this.queueState = QueueState.SHUTDOWN;

			if (readerQueue.size() > 0 && this.itemQueue.getTotalCount() == 0) {
				outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class,
						readerQueue.size());

				// manually copy over values because only cloning of primitives are allowed
				int i = 0;
				while (!readerQueue.isEmpty()) {
					outstandingReaders[i++] = readerQueue.remove();
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

	@SuppressWarnings("unchecked")
	protected void dispose() {
		boolean dispose = false;

		synchronized (thisLock) {
			if (queueState != QueueState.CLOSED) {
				queueState = QueueState.CLOSED;
				dispose = true;
			}
		}

		if (dispose) {
			while (readerQueue.size() > 0) {
				CompletableFuture<T> reader = readerQueue.remove();
				reader.complete((T) new Item());
			}

			while (itemQueue.hasAnyItem()) {
				Item item = itemQueue.dequeueAnyItem();
				disposeItem(item);
				invokeDequeuedCallback(item);
			}
		}
	}

	void disposeItem(Item item) {
		if (item.getValue() != null && this.disposeItemCallback != null) {
			this.disposeItemCallback.accept(item.getValue());
		}
	}

	@SuppressWarnings("unchecked")
	void completeOutstandingReadersCallback(Object state) {
		CompletableFuture<T>[] outstandingReaders = (CompletableFuture<T>[]) state;

		for (int i = 0; i < outstandingReaders.length; i++) {
			outstandingReaders[i].complete((T) new Item());
		}
	}

	static void completeWaiters(boolean itemAvailable, CompletableFuture<Boolean>[] waiters) {
		for (int i = 0; i < waiters.length; i++) {
			waiters[i].complete(itemAvailable);
		}
	}

	@SuppressWarnings("unchecked")
	static void completeWaitersFalseCallback(Object state) {
		completeWaiters(false, (CompletableFuture<Boolean>[]) state);
	}

	static void completeWaitersLater(boolean itemAvailable, CompletableFuture<Boolean>[] waiters) {
		if (itemAvailable) {
			ActionItem.schedule(s -> completeWaitersTrueCallback(s), waiters);
		} else {
			ActionItem.schedule(s -> completeWaitersFalseCallback(s), waiters);
		}
	}

	@SuppressWarnings("unchecked")
	static void completeWaitersTrueCallback(Object state) {
		completeWaiters(true, (CompletableFuture<Boolean>[]) state);
	}

	void invokeDequeuedCallback(Item item) {
		if (item != null && item.getDequeuedCallback() != null) {
			item.dequeuedCallback.accept(item.getValue());
		}
	}

	void invokeDequeuedCallbackLater(Item item) {
		if (item != null && item.getDequeuedCallback() != null) {
			ActionItem.schedule(s -> onInvokeDequeuedCallback(s), item);
		}
	}

	@SuppressWarnings("unchecked")
	void onDispatchCallback(Object state) {
		((InputQueue<T>) state).dispatch();
	}

	@SuppressWarnings("unchecked")
	void onInvokeDequeuedCallback(Object state) {
		Item item = (Item) state;
		item.getDequeuedCallback().accept(item.getValue());
	}

	void enqueueAndDispatch(Item item, boolean canDispatchOnThisThread) {
		boolean disposeItem = false;
		CompletableFuture<T> reader = null;
		boolean dispatchLater = false;
		CompletableFuture<Boolean>[] waiters = null;
		boolean itemAvailable = true;

		synchronized (thisLock) {
			itemAvailable = !((queueState == QueueState.CLOSED) || (queueState == QueueState.SHUTDOWN));
			waiters = this.getWaiters();

			if (queueState == QueueState.OPEN) {
				if (canDispatchOnThisThread) {
					if (this.readerQueue.size() == 0) {
						itemQueue.enqueueAvailableItem(item);
					} else {
						reader = this.readerQueue.remove();
					}
				} else {
					if (readerQueue.size() == 0) {
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

		if (waiters != null) {
			if (canDispatchOnThisThread) {
				completeWaiters(itemAvailable, waiters);
			} else {
				completeWaitersLater(itemAvailable, waiters);
			}
		}

		if (reader != null) {
			invokeDequeuedCallback(item);
			reader.complete((T) item.getValue());
		}

		if (dispatchLater) {
			ActionItem.schedule(s -> onDispatchCallback(s), this);
		} else if (disposeItem) {
			invokeDequeuedCallback(item);
			disposeItem(item);
		}
	}

	// This will not block, however, Dispatch() must be called later if this
	// function returns true.
	boolean enqueueWithoutDispatch(Item item) {
		synchronized (thisLock) {
			if (queueState != QueueState.CLOSED && queueState != QueueState.SHUTDOWN) {

				if (readerQueue.size() == 0 && waiterList.size() == 0) {
					itemQueue.enqueueAvailableItem(item);
					return false;
				} else {
					itemQueue.enqueuePendingItem(item);
					return true;
				}
			}
		}

		disposeItem(item);
		invokeDequeuedCallbackLater(item);
		return false;
	}

	CompletableFuture<Boolean>[] getWaiters() {
		CompletableFuture<Boolean>[] waiters = null;

		if (waiterList.size() > 0) {
			waiters = waiterList.toArray(waiters);
			waiterList.clear();
		}
		return waiters;
	}

	// Used for timeouts. The InputQueue must remove readers from its reader queue
	// to prevent dispatching items to timed out readers.
	boolean removeReader(CompletableFuture<T> reader) {
		synchronized (thisLock) {
			if (queueState == QueueState.OPEN || queueState == QueueState.SHUTDOWN) {
				boolean removed = false;

				for (int i = readerQueue.size(); i > 0; i--) {
					CompletableFuture<T> temp = readerQueue.remove();

					if (temp == reader) {
						removed = true;
					} else {
						readerQueue.add(temp);
					}
				}
				return removed;
			}
		}
		return false;
	}

	enum QueueState {
		OPEN, SHUTDOWN, CLOSED
	}

	class Item {
		Consumer<T> dequeuedCallback;
		Exception exception;
		T value;

		// Simulate empty struct constructor in C#
		protected Item() {
			this(null, null, null);
		}

		protected Item(T value, Consumer<T> dequeuedCallback) {
			this(value, null, dequeuedCallback);
		}

		protected Item(Exception exception, Consumer<T> dequeuedCallback) {
			this(null, exception, dequeuedCallback);
		}

		Item(T value, Exception exception, Consumer<T> dequeuedCallback) {
			this.value = value;
			this.exception = exception;
			this.dequeuedCallback = dequeuedCallback;
		}

		protected Consumer<T> getDequeuedCallback() {
			return this.dequeuedCallback;
		}

		protected Exception getException() {
			return this.exception;
		}

		protected T getValue() {
			return this.value;
		}

		protected T getValueWithException() {
			if (this.exception != null) {
				// TODO: trace
//                throw RelayEventSource.Log.ThrowingException(this.exception, this, EventLevel.Informational);
			}

			return this.value;
		}
	}

	class ItemQueue {
		int head;
		Item[] items;
		int pendingCount;
		int totalCount;

		@SuppressWarnings("unchecked")
		protected ItemQueue() {
			this.items = (Item[]) Array.newInstance(Item.class, 1);
		}

		// same as ItemCount
		protected int getTotalCount() {
			return this.totalCount;
		}

		protected boolean hasAnyItem() {
			return this.totalCount > 0;
		}

		protected boolean hasAvailableItem() {
			return this.totalCount > this.pendingCount;
		}

		protected Item dequeueAnyItem() {
			if (this.pendingCount == this.totalCount) {
				this.pendingCount--;
			}

			return dequeueItemCore();
		}

		protected Item dequeueAvailableItem() {
			if (this.totalCount == this.pendingCount) {
				throw new RuntimeException("ItemQueue does not contain any available items");
			}

			return dequeueItemCore();
		}

		protected void enqueueAvailableItem(Item item) {
			enqueueItemCore(item);
		}

		protected void enqueuePendingItem(Item item) {
			enqueueItemCore(item);
			this.pendingCount++;
		}

		protected void makePendingItemAvailable() {
			// TODO: trace
//            if (pendingCount == 0) {
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException("ItemQueue does not contain any pending items"), this);
//            }

			this.pendingCount--;
		}

		Item dequeueItemCore() {
			// TODO: trace
//            if (totalCount == 0) {
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException("ItemQueue does not contain any items"), this);
//            }

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
