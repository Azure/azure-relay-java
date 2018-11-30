package com.microsoft.azure.relay;

import java.lang.reflect.Array;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class InputQueue<T extends Object> {
	// Fields
    private final ItemQueue itemQueue;
// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//  private final Queue<IQueueReader> readerQueue;
//  private final List<IQueueWaiter> waiterList;
    private final Queue<CompletableFuture<T>> readerQueue;
    private final List<CompletableFuture<T>> waiterList;

    private QueueState queueState;
    private Object thisLock = new Object();

    // Properties
    private int pendingCount;
    private int readersQueueCount;
    // Users like ServiceModel can hook this abort ICommunicationObject or handle other non-IDisposable objects
    private Consumer<T> disposeItemCallback;
    
    public int getPendingCount() {
    	synchronized(thisLock) {
        	return this.pendingCount;    		
    	}
    }
    
    public int getReadersQueueCount() {
    	synchronized(thisLock) {
    		return this.readerQueue.size();
    	}
    }
    
    public Consumer<T> getDisposeItemCallback() {
    	return this.disposeItemCallback;
    }
    public void setDisposeItemCallback(Consumer<T> callback) {
    	this.disposeItemCallback = callback;
    }
    
    public InputQueue() {
        this.itemQueue = new ItemQueue();
        // TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//        this.readerQueue = new Queue<IQueueReader>();
//        this.waiterList = new List<IQueueWaiter>();
        this.readerQueue = new LinkedList<CompletableFuture<T>>();
        this.waiterList = new LinkedList<CompletableFuture<T>>();
        this.queueState = QueueState.OPEN;
    }

    // TODO: cancellationtoken
    public CompletableFuture<T> dequeueAsync() {
        return this.dequeueAsync(null);
    }

    // TODO: cancellationtoken
    public CompletableFuture<T> dequeueAsync(Object state) {
        Item item = null;
        
        synchronized(thisLock) {
        	
            if (this.queueState == QueueState.OPEN) {
            	
                if (itemQueue.hasAvailableItem()) {
                    item = itemQueue.dequeueAvailableItem();
                }
                else {
                	// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//                	AsyncQueueReader reader = new AsyncQueueReader(this, state);
//                    this.readerQueue.Enqueue(reader);
//                    return reader.Task;
                	CompletableFuture<T> reader = new CompletableFuture<T>();
                	this.readerQueue.add(reader);
                	return reader;
                }
            }
            else if (queueState == QueueState.SHUTDOWN) {
            	
                if (itemQueue.hasAvailableItem()) {
                    item = itemQueue.dequeueAvailableItem();
                }
                else if (itemQueue.hasAnyItem()) {
                	// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//                    AsyncQueueReader reader = new AsyncQueueReader(this, state);
//                    this.readerQueue.Enqueue(reader);
//                    return reader.Task;
                	CompletableFuture<T> reader = new CompletableFuture<T>();
                	this.readerQueue.add(reader);
                	return reader;
                }
            }
        }

        invokeDequeuedCallback(item);
        return (item != null) ? CompletableFuture.completedFuture(item.getValue()) : CompletableFuture.completedFuture(null);
    }

    // TODO: cancellationtoken
    public CompletableFuture<Boolean> waitForItemAsync() {
        return this.waitForItemAsync(null);
    }

    // TODO: cancellationtoken
    @SuppressWarnings("unchecked")
	public CompletableFuture<Boolean> waitForItemAsync(Object state) {
    	
        synchronized (thisLock) {
            if (queueState == QueueState.OPEN) {
                if (!itemQueue.hasAvailableItem()) {
                	// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//                    AsyncQueueWaiter waiter = new AsyncQueueWaiter(state);
//                    waiterList.add(waiter);
//                    return waiter.Task;
                    CompletableFuture<T> waiter = new CompletableFuture<T>();
                    waiterList.add(waiter);
                    return (CompletableFuture<Boolean>) waiter;
                }
            }
            else if (queueState == QueueState.SHUTDOWN) {
                if (!itemQueue.hasAvailableItem() && itemQueue.hasAnyItem()) {
                	// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//                    AsyncQueueWaiter waiter = new AsyncQueueWaiter(cancellationToken, state);
//                    waiterList.Add(waiter);
//                    return waiter.Task;
                    CompletableFuture<T> waiter = new CompletableFuture<T>();
                    waiterList.add(waiter);
                    return (CompletableFuture<Boolean>) waiter;
                }
            }
        }

        return CompletableFuture.completedFuture(true);
    }

    public void close() {
        dispose();
    }

    @SuppressWarnings("unchecked")
	public void dispatch() {
    	// TODO: fall back to IQueueReader/IQueueWaiter if completablefuture does not work out
//        IQueueReader reader = null;
//        IQueueReader[] outstandingReaders = null;
//        IQueueWaiter[] waiters = null;
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
                        outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class, readerQueue.size());
                        
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

    public void enqueueAndDispatch(T item) {
        enqueueAndDispatch(item, null);
    }

    // dequeuedCallback is called as an item is dequeued from the InputQueue.  The 
    // InputQueue lock is not held during the callback.  However, the user code will
    // not be notified of the item being available until the callback returns.  If you
    // are not sure if the callback will block for a long time, then first call 
    // ActionItem.Schedule to get to a "safe" thread.
    public void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback) {
    	enqueueAndDispatch(item, dequeuedCallback, true);
    }

    public void enqueueAndDispatch(T item, Consumer<T> dequeuedCallback, boolean canDispatchOnThisThread) {
    	// TODO: fx
//    	if (item instanceof Exception)
//      Fx.Assert(exception != null, "EnqueueAndDispatch: exception parameter should not be null");
    	// TODO: assert
//        Fx.Assert(item != null, "EnqueueAndDispatch: item parameter should not be null");
        enqueueAndDispatch(new Item(item, dequeuedCallback), canDispatchOnThisThread);
    }

    public boolean enqueueWithoutDispatch(T item, Consumer<T> dequeuedCallback) {
    	// TODO: fx
//        Fx.Assert(item != null, "EnqueueWithoutDispatch: item parameter should not be null");
        return enqueueWithoutDispatch(new Item(item, dequeuedCallback));
    }

    public boolean enqueueWithoutDispatch(Exception exception, Consumer<T> dequeuedCallback) {
    	// TODO: fx
//        Fx.Assert(exception != null, "EnqueueWithoutDispatch: exception parameter should not be null");
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

            if (readerQueue.size() > 0 && this.itemQueue.getTotalCount() == 0) {
                outstandingReaders = (CompletableFuture<T>[]) Array.newInstance(CompletableFuture.class, readerQueue.size());
                
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
                outstandingReaders[i].complete((T) new Item(exception, null));
            }
        }
    }

    @SuppressWarnings("unchecked")
	public void dispose() {
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

        for (int i = 0; i < outstandingReaders.length; i++)
        {
            outstandingReaders[i].complete((T) new Item());
        }
    }

    static void completeWaiters(boolean itemAvailable, CompletableFuture<Boolean>[] waiters) {
        for (int i = 0; i < waiters.length; i++)
        {
            waiters[i].complete(itemAvailable);
        }
    }

	@SuppressWarnings("unchecked")
	static void completeWaitersFalseCallback(Object state) {
        completeWaiters(false, (CompletableFuture<Boolean>[]) state);
    }

    static void completeWaitersLater(boolean itemAvailable, CompletableFuture<Boolean>[] waiters) {
        if (itemAvailable)
        {
            ActionItem.schedule(s -> completeWaitersTrueCallback(s), waiters);
        }
        else
        {
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
    	// TODO: fx
//        Fx.Assert(state != null, "InputQueue.OnInvokeDequeuedCallback: (state != null)");

		Item item = (Item) state;
        item.getDequeuedCallback().accept(item.getValue());
    }

    @SuppressWarnings("unchecked")
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
                    }
                    else {
                        reader = this.readerQueue.remove();
                    }
                }
                else {
                    if (readerQueue.size() == 0) {
                        itemQueue.enqueueAvailableItem(item);
                    }
                    else {
                        itemQueue.enqueuePendingItem(item);
                        dispatchLater = true;
                    }
                }
            }
            else { // queueState == QueueState.Closed || queueState == QueueState.Shutdown
                disposeItem = true;
            }
        }

        if (waiters != null) {
            if (canDispatchOnThisThread) {
                completeWaiters(itemAvailable, waiters);
            }
            else {
                completeWaitersLater(itemAvailable, waiters);
            }
        }

        if (reader != null) {
            invokeDequeuedCallback(item);
            reader.complete((T) item.getValue());
        }

        if (dispatchLater) {
            ActionItem.schedule(s -> onDispatchCallback(s), this);
        }
        else if (disposeItem) {
            invokeDequeuedCallback(item);
            disposeItem(item);
        }
    }

    // This will not block, however, Dispatch() must be called later if this function
    // returns true.
    boolean enqueueWithoutDispatch(Item item)
    {
        synchronized (thisLock) {
            // Open
            if (queueState != QueueState.CLOSED && queueState != QueueState.SHUTDOWN)
            {
                if (readerQueue.size() == 0 && waiterList.size() == 0)
                {
                    itemQueue.enqueueAvailableItem(item);
                    return false;
                }
                else
                {
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

    // Used for timeouts. The InputQueue must remove readers from its reader queue to prevent
    // dispatching items to timed out readers.
    boolean removeReader(CompletableFuture<T> reader) {
    	// TODO: fx
//        Fx.Assert(reader != null, "InputQueue.RemoveReader: (reader != null)");

        synchronized (thisLock) {
        	
            if (queueState == QueueState.OPEN || queueState == QueueState.SHUTDOWN) {
                boolean removed = false;

                for (int i = readerQueue.size(); i > 0; i--) {
                    CompletableFuture<T> temp = readerQueue.remove();
                    
                    if (temp == reader) {
                        removed = true;
                    }
                    else {
                        readerQueue.add(temp);
                    }
                }

                return removed;
            }
        }

        return false;
    }

    
    enum QueueState
    {
        OPEN,
        SHUTDOWN,
        CLOSED
    }

//    interface IQueueReader
//    {
//        void Set(Item item);
//    }
//
//    interface IQueueWaiter
//    {
//        void Set(boolean itemAvailable);
//    }

    class Item
    {
    	Consumer<T> dequeuedCallback;
        Exception exception;
        T value;
        
        // Simulate empty struct constructor in C#
        public Item() {
        	this(null, null, null);
        }

        public Item(T value, Consumer<T> dequeuedCallback) {
        	this(value, null, dequeuedCallback);
        }

        public Item(Exception exception, Consumer<T> dequeuedCallback) {
        	this(null, exception, dequeuedCallback);
        }

        Item(T value, Exception exception, Consumer<T> dequeuedCallback) {
            this.value = value;
            this.exception = exception;
            this.dequeuedCallback = dequeuedCallback;
        }

        public Consumer<T> getDequeuedCallback() {
            return this.dequeuedCallback;
        }

        public Exception getException() {
            return this.exception;
        }

        public T getValue() {
            return this.value;
        }
        
        public T getValueWithException() {
            if (this.exception != null)
            {
            	 // TODO: trace
//                throw RelayEventSource.Log.ThrowingException(this.exception, this, EventLevel.Informational);
            }

            return this.value;
        }
    }

    // TODO: tasksource
//    class AsyncQueueReader : TaskCompletionSource<T>, IQueueReader
//    {
//        private final InputQueue<T> inputQueue;
//        readonly CancellationTokenRegistration cancelRegistration;
//
//        // TODO: cancellationToken
//        public AsyncQueueReader(InputQueue<T> inputQueue, object state)
//            : base(state)
//        {
//            this.inputQueue = inputQueue;
//            this.cancelRegistration = cancellationToken.Register(s => CancelCallback(s), this);
//        }
//
//        public void Set(Item inputItem)
//        {
//            this.cancelRegistration.Dispose();
//
//            if (inputItem.Exception != null)
//            {
//                this.TrySetException(inputItem.Exception);
//            }
//            else
//            {
//                this.TrySetResult(inputItem.Value);
//            }
//        }
//
//        void cancelCallback(object state) {
//            AsyncQueueReader thisPtr = (AsyncQueueReader)state;
//            thisPtr.cancelRegistration.Dispose();
//            if (thisPtr.inputQueue.RemoveReader(thisPtr))
//            {
//                thisPtr.TrySetCanceled();
//            }
//        }
//    }

    // TODO: tasksource
//    class AsyncQueueWaiter : TaskCompletionSource<bool>, IQueueWaiter
//    {
//        readonly CancellationTokenRegistration cancelRegistration;
//
//        // TODO: cancellationtoken
//        public AsyncQueueWaiter(Object state)
//            : base(state)
//        {
//            this.cancelRegistration = cancellationToken.Register(s => cancelCallback(s), this);
//        }
//
//        public void Set(bool currentItemAvailable)
//        {
//            this.cancelRegistration.Dispose();
//            this.TrySetResult(currentItemAvailable);
//        }
//
//        static void cancelCallback(object state)
//        {
//            var thisPtr = (AsyncQueueWaiter)state;
//            thisPtr.cancelRegistration.Dispose();
//            thisPtr.TrySetCanceled();
//        }
//    }

    class ItemQueue {
        int head;
        Item[] items;
        int pendingCount;
        int totalCount;
        
        @SuppressWarnings("unchecked")
		public ItemQueue()
        {
            this.items = (Item[]) Array.newInstance(Item.class, 1);
        }
        
        // same as ItemCount
        public int getTotalCount() {
        	return this.totalCount;
        }
        
        public boolean hasAnyItem() {
        	return this.totalCount > 0;
        }
        
        public boolean hasAvailableItem() {
        	return this.totalCount > this.pendingCount;
        }

        public Item dequeueAnyItem()
        {
            if (this.pendingCount == this.totalCount) {
                this.pendingCount--;
            }
            return dequeueItemCore();
        }

        public Item dequeueAvailableItem()
        {
            if (this.totalCount == this.pendingCount)
            {
            	// TODO: fx
//                Fx.Assert(this.totalCount != this.pendingCount, "ItemQueue does not contain any available items");
            	// TODO: trace
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException("ItemQueue does not contain any available items"), this);
            	throw new RuntimeException("ItemQueue does not contain any available items");
            }

            return dequeueItemCore();
        }

        public void enqueueAvailableItem(Item item)
        {
            enqueueItemCore(item);
        }

        public void enqueuePendingItem(Item item)
        {
            enqueueItemCore(item);
            this.pendingCount++;
        }

        public void makePendingItemAvailable()
        {
            if (pendingCount == 0)
            {
            	// TODO: fx
//                Fx.Assert(this.pendingCount != 0, "ItemQueue does not contain any pending items");
                // TODO: trace
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException("ItemQueue does not contain any pending items"), this);
            }

            this.pendingCount--;
        }

        Item dequeueItemCore()
        {
            if (totalCount == 0)
            {
            	throw new IllegalArgumentException("Item queue is empty, cannot dequeue.");
            	// TODO: trace
//                Fx.Assert(totalCount != 0, "ItemQueue does not contain any items");
//                throw RelayEventSource.Log.ThrowingException(new InvalidOperationException("ItemQueue does not contain any items"), this);
            }

            Item item = this.items[this.head];
            this.items[this.head] = new Item();
            this.totalCount--;
            this.head = (this.head + 1) % this.items.length;
            return item;
        }

        @SuppressWarnings("unchecked")
		void enqueueItemCore(Item item)
        {
            if (this.totalCount == this.items.length)
            {
                Item[] newItems = (Item[]) Array.newInstance(Item.class, this.items.length * 2);
                for (int i = 0; i < this.totalCount; i++)
                {
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
