package com.microsoft.azure.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.Test;

import com.microsoft.azure.relay.AsyncSemaphore.LockRelease;

public class AsyncSemaphoreTest {
	private static final Duration TIMEOUT = Duration.ofMillis(30);
	
	@Test (expected = java.util.concurrent.TimeoutException.class)
	public void simpleLockAndReleaseTest() throws Throwable {
		AsyncSemaphore sem = new AsyncSemaphore(2);
		CompletableFuture<LockRelease> release1 = sem.acquireAsync(TIMEOUT);
		CompletableFuture<LockRelease> release2 = sem.acquireAsync(TIMEOUT);
		CompletableFuture<LockRelease> release3 = sem.acquireAsync(TIMEOUT);
		
		assertNotNull("release1 was not a valid release", release1.join());
		assertNotNull("release2 was not a valid release", release2.join());
		
		try {
			release3.join();
		} catch (CompletionException e) {
			assertEquals("Semaphore should be unavailable", 0, sem.availablePermits());
			release1.join().release();
			throw e.getCause();
		}
	}
	
	@Test
	public void lockandReleaseOverLimitTest() throws Throwable {
		int size = 3;
		AsyncSemaphore sem = new AsyncSemaphore(size);
		boolean lockExceptionThrown = false;
		
		try {
			sem.acquireAsync(size + 1).join();
		} catch (Exception e) {
			lockExceptionThrown = true;
		}
		assertTrue("Should have thrown when trying to acquire " + size+1 + " when limit was " + size, lockExceptionThrown);
		lockExceptionThrown = false;
		
		LockRelease release = sem.acquireAsync(size).join();
		try {
			release.release(size + 1);
		} catch (IllegalArgumentException e) {
			lockExceptionThrown = true;
		}
		assertTrue("Should have thrown when trying to release " + size+1 + " when limit was " + size, lockExceptionThrown);
	}
	
	@Test
	public void repeatedLockReleaseTest() {
		int size = 3;
		AsyncSemaphore sem = new AsyncSemaphore(size);
		boolean lockExceptionThrown = false;
		
		LockRelease release1 = sem.acquireAsync(2).join();
		CompletableFuture<LockRelease> release2 = sem.acquireAsync(2);
		try {
			release1.release(1);
			release2.join();
		} catch (Exception e) {
			lockExceptionThrown = true;
		}
		assertFalse("Should have been an valid operation", lockExceptionThrown);
	}
}
