package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
		release.get().release();
		assertFalse("AsyncLock is still locked after release.", lock.isLocked());
	}
	
	@Test
	public void simpleLockAndReleaseTest() {
		lock.lockAsync().thenAccept((lockRelease) -> {
			release.set(lockRelease);
			assertTrue("AsyncLock is not locked when the lock was idle.", lock.isLocked());
		}).join();
	}
	
	@Test (expected = java.util.concurrent.TimeoutException.class)
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
		}
		catch (CompletionException e) {
			throw e.getCause();
		}
		finally {
			
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
			assertTrue("Task should have been completed synchronously while waiting for lock.", taskToComplete.isDone());
		});
		
		// This task should complete while task2 is waiting for the lock
		taskToComplete.complete(null);
		
		task1.join();
		task2.join();
	}
}
