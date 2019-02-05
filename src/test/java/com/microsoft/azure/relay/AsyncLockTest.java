package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import com.microsoft.azure.relay.AsyncLock.LockRelease;

public class AsyncLockTest {
	private static final long TIMEOUT_MS = 30;
	private final AsyncLock lock = new AsyncLock();
	private static AtomicReference<LockRelease> release = new AtomicReference<AsyncLock.LockRelease>(null);

	@After
	public void cleanup() {
		LockRelease lockRelease = release.get();
		if (lockRelease != null) {
			lockRelease.release();
		}
		assertFalse("AsyncLock is still locked after release.", lock.isLocked());
	}

	@Test
	public void simpleLockAndReleaseTest() {
		lock.lockAsync().thenAccept((lockRelease) -> {
			release.set(lockRelease);
			assertTrue("AsyncLock is not locked when the lock was idle.", lock.isLocked());
		}).join();
	}

	@Test(expected = java.util.concurrent.TimeoutException.class)
	public void timeoutLockAndReleaseTest() throws Throwable {
		CompletableFuture<LockRelease> task1 = lock.lockAsync();

		try {
			task1.thenAccept((lockRelease) -> {
				release.set(lockRelease);
				assertTrue("AsyncLock is not locked when the lock was idle.", lock.isLocked());

				CompletableFuture<LockRelease> task2 = lock.lockAsync(Duration.ofMillis(TIMEOUT_MS));
				assertFalse("AsyncLock is acquired after it was already locked.", task2.isDone());

				task2.join();
			}).join();
		} catch (CompletionException e) {
			throw e.getCause();
		}
	}

	@Test
	public void ensureAsyncLockIsAsyncTest() {
		CompletableFuture<Void> taskToComplete = new CompletableFuture<Void>();

		CompletableFuture<Void> task1 = lock.lockAsync().thenAcceptAsync(lockRelease -> {
			try {
				release.set(lockRelease);
				Thread.sleep(TIMEOUT_MS);
			} catch (InterruptedException e) {
				fail("Task interrupted when it shouldn't have been");
			}
			lockRelease.release();
		});

		CompletableFuture<Void> task2 = lock.lockAsync().thenAcceptAsync(lockRelease -> {
			release.set(lockRelease);
			assertTrue("Task should have been completed synchronously while waiting for lock.",
					taskToComplete.isDone());
		});

		// This task should complete while task2 is waiting for the lock
		taskToComplete.complete(null);

		task1.join();
		task2.join();
	}

	@Test
	public void ensureAsyncLockIsAsyncTest2() throws InterruptedException, ExecutionException, TimeoutException {
		// I don't see much value in all tests using a single AsyncLock, but there is a
		// down-side that they could interfere with each other.
		AsyncLock asyncLock = new AsyncLock();
		CompletableFuture<LockRelease> lockFuture1 = asyncLock.lockAsync();
		CompletableFuture<LockRelease> lockFuture2 = asyncLock.lockAsync();
		assertTrue("First Lock should be acquired right away", lockFuture1.isDone());
		Thread.sleep(TIMEOUT_MS);
		assertFalse("Second Lock should not be acquired yet", lockFuture2.isDone());
		LockRelease lockRelease1 = lockFuture1.join();
		lockRelease1.release();

		// The pending lockAsync should be released soon but may require thread switches
		// so use wait with a timeout to give it a little time without waiting any
		// longer than needed.
		final int WAIT_TIMEOUT_MS = 2000;
		LockRelease lockRelease2 = lockFuture2.get(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		assertTrue("Second lock should be available now", lockFuture2.isDone());
		lockRelease2.release();
	}

	@Test
	public void lockTimeoutTest() throws InterruptedException, ExecutionException, TimeoutException {
		// I don't see much value in all tests using a single AsyncLock,
		// but there is a down-side that they could interfere with each other.
		AsyncLock asyncLock = new AsyncLock();
		CompletableFuture<LockRelease> lockFuture1 = asyncLock.lockAsync();
		CompletableFuture<LockRelease> lockFuture2 = asyncLock.lockAsync(Duration.ofMillis(10));
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
		CompletableFuture<LockRelease> lockFuture3 = asyncLock.lockAsync();
		LockRelease lockRelease3 = lockFuture3.get(2000, TimeUnit.MILLISECONDS);
		lockRelease3.release();
	}
}
