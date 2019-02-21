package com.microsoft.azure.relay;

import static org.junit.Assert.*;
import static com.microsoft.azure.relay.TestUtil.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import com.microsoft.azure.relay.AsyncSemaphore.LockRelease;

public class AsyncLockTest {
	private static final long TIMEOUT_MS = 30;
	private static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	
	@Test
	public void simpleLockAndReleaseTest() {
		AsyncLock lock = new AsyncLock();
		lock.acquireAsync(EXECUTOR).thenAccept((lockRelease) -> {
			assertTrue("AsyncLock is not locked when the lock was idle.", lock.isLocked());
			lockRelease.release();
			assertFalse("AsyncLock is still locked after release.", lock.isLocked());
		}).join();
	}

	@Test(expected = java.util.concurrent.TimeoutException.class)
	public void timeoutLockAndReleaseTest() throws Throwable {
		AsyncLock lock = new AsyncLock();
		AtomicReference<LockRelease> release = new AtomicReference<AsyncLock.LockRelease>(null);
		CompletableFuture<LockRelease> task1 = lock.acquireAsync(EXECUTOR);

		try {
			task1.thenAccept((lockRelease) -> {
				release.set(lockRelease);
				assertTrue("AsyncLock is not locked when the lock was idle.", lock.isLocked());

				CompletableFuture<LockRelease> task2 = lock.acquireAsync(Duration.ofMillis(TIMEOUT_MS), EXECUTOR);
				assertFalse("AsyncLock is acquired after it was already locked.", task2.isDone());

				task2.join().release();
			}).join();
		} catch (CompletionException e) {
			release.get().release();
			throw e.getCause();
		}
	}

	@Test
	public void ensureAsyncLockIsAsyncTest() {
		AsyncLock lock = new AsyncLock();
		CompletableFuture<Void> taskToComplete = new CompletableFuture<Void>();

		CompletableFuture<Void> task1 = lock.acquireAsync(EXECUTOR).thenAcceptAsync(lockRelease -> {
			try {
				Thread.sleep(TIMEOUT_MS);
			} catch (InterruptedException e) {
				fail("Task interrupted when it shouldn't have been");
			}
			lockRelease.release();
		});

		CompletableFuture<Void> task2 = lock.acquireAsync(EXECUTOR).thenAcceptAsync(lockRelease -> {
			assertTrue("Task should have been completed synchronously while waiting for lock.",
					taskToComplete.isDone());
			lockRelease.release();
		});

		// This task should complete while task2 is waiting for the lock
		taskToComplete.complete(null);

		task1.join();
		task2.join();
	}

	@Test
	public void ensureAsyncLockIsAsyncTest2() throws InterruptedException, ExecutionException, TimeoutException {
		AsyncLock asyncLock = new AsyncLock();
		CompletableFuture<LockRelease> lockFuture1 = asyncLock.acquireAsync(EXECUTOR);
		CompletableFuture<LockRelease> lockFuture2 = asyncLock.acquireAsync(EXECUTOR);
		assertTrue("First Lock should be acquired right away", lockFuture1.isDone());
		Thread.sleep(TIMEOUT_MS);
		assertFalse("Second Lock should not be acquired yet", lockFuture2.isDone());
		LockRelease lockRelease1 = lockFuture1.join();
		lockRelease1.release();

		// The pending lockAsync should be released soon but may require thread switches
		// so use wait with a timeout to give it a little time without waiting any longer than needed.
		final int WAIT_TIMEOUT_MS = 2000;
		LockRelease lockRelease2 = lockFuture2.get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		assertTrue("Second lock should be available now", lockFuture2.isDone());
		lockRelease2.release();
	}

	@Test
	public void lockTimeoutTest() throws InterruptedException, ExecutionException, TimeoutException {
		AsyncLock asyncLock = new AsyncLock();
		CompletableFuture<LockRelease> lockFuture1 = asyncLock.acquireAsync(EXECUTOR);
		CompletableFuture<LockRelease> lockFuture2 = asyncLock.acquireAsync(Duration.ofMillis(10), EXECUTOR);
		assertTrue("lockFuture1 should be done (completed sync)", lockFuture1.isDone());
		assertFalse("lockFuture2 should not be done", lockFuture2.isDone());

		try {
			LockRelease lockRelease2 = lockFuture2.get();
			lockRelease2.release();
			assertTrue("lockFuture2.get() should have thrown.", false);
		} catch (ExecutionException e) {
			assertTrue("Cause should be TimeoutException", e.getCause().getClass() == TimeoutException.class);
		}

		// Release the lock
		lockFuture1.join().release();

		// Acquire uncontended lock (ideally should be sync)
		CompletableFuture<LockRelease> lockFuture3 = asyncLock.acquireAsync(EXECUTOR);
		LockRelease lockRelease3 = lockFuture3.get(2000, TimeUnit.MILLISECONDS);
		lockRelease3.release();
	}
	
	@Test
	public void lockScopeUncontended() throws Exception {
		String originalString = "lockScopeUncontended";
		AsyncLock lock = new AsyncLock();
		assertFalse("Should not be locked to start", lock.isLocked());
		
		CompletableFuture<String> innerTask = new CompletableFuture<String>();
		CompletableFuture<String> outerTask = lock.runInsideLockAsync(null, EXECUTOR, () -> {
			assertTrue("Should be locked during Inner Function", lock.isLocked());
			return innerTask;
		});
		
		assertFalse(outerTask.isDone());
		assertTrue("Should be locked now", lock.isLocked());
		innerTask.complete(originalString);
		String resultString = outerTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		assertEquals(originalString, resultString);
		assertFalse(lock.isLocked());
	}
	
	@Test
	public void lockScopeContended() throws Throwable {
		String originalString = "lockScopeContended";
		AsyncLock lock = new AsyncLock();
		LockRelease lockRelease = lock.acquireAsync(EXECUTOR).join();
		assertTrue("Should be locked now", lock.isLocked());
		
		CompletableFuture<Void> innerTaskStart = new CompletableFuture<Void>();
		CompletableFuture<String> innerTaskResult = new CompletableFuture<String>();
		CompletableFuture<String> outerTask = lock.runInsideLockAsync(null, EXECUTOR, () -> {
			assertTrue("Should be locked during Inner Function", lock.isLocked());
			innerTaskStart.complete(null);			
			return innerTaskResult;
		});
		
		assertFalse(outerTask.isDone());
		assertTrue("Should be locked now", lock.isLocked());
		innerTaskResult.complete(originalString);
		assertThrows(TimeoutException.class, (Executable)() -> innerTaskStart.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		
		lockRelease.release();
		innerTaskStart.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

		String resultString = outerTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		assertEquals(originalString, resultString);
		assertFalse(lock.isLocked());
	}
	
	@Test
	public void lockScopeContendedThrow() throws Throwable {
		AsyncLock lock = new AsyncLock();
		LockRelease lockRelease = lock.acquireAsync(EXECUTOR).join();
		assertTrue("Should be locked now", lock.isLocked());
		
		CompletableFuture<Void> innerTaskStart = new CompletableFuture<Void>();
		CompletableFuture<String> outerTask = lock.runInsideLockAsync(null, EXECUTOR, () -> {
			assertTrue("Should be locked during Inner Function", lock.isLocked());
			innerTaskStart.complete(null);			
			throw new CompletionException(new IOException("Test Induced Exception"));
		});
		
		assertTrue("Should be locked now", lock.isLocked());
		assertThrows(TimeoutException.class, (Executable)() -> outerTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		assertThrows(TimeoutException.class, (Executable)() -> innerTaskStart.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		
		lockRelease.release();
		Throwable error = assertThrows(ExecutionException.class, (Executable)() -> outerTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		assertEquals(IOException.class, error.getCause().getClass());
		assertFalse(lock.isLocked());
	}
	
	@Test
	public void lockScopeUncontendedThrow() throws Throwable {
		AsyncLock lock = new AsyncLock();	
		CompletableFuture<Void> innerTaskStart = new CompletableFuture<Void>();
		CompletableFuture<String> outerTask = lock.runInsideLockAsync(null, EXECUTOR, () -> {
			assertTrue("Should be locked during Inner Function", lock.isLocked());
			innerTaskStart.complete(null);			
			throw new CompletionException(new IOException("Test Induced Exception"));
		});
		
		innerTaskStart.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		Throwable error = assertThrows(ExecutionException.class, (Executable)() -> outerTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
		assertEquals(IOException.class, error.getCause().getClass());
		assertFalse(lock.isLocked());
	}
}
