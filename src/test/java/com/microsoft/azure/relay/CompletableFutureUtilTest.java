package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.time.Duration;
import org.junit.After;
import org.junit.Test;

public class CompletableFutureUtilTest {
	private static final AutoShutdownScheduledExecutor EXECUTOR = AutoShutdownScheduledExecutor.Create();
	private static final int SHORT_MS = 20;
	private static final int LONG_MS = 40;
	private static boolean testBool = false;

	
	@After
	public void resetBool() {
		testBool = false;
	}
	
	@Test
	public void timedRunAsyncCompletionTest() {
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(LONG_MS), () -> {
			try {
				Thread.sleep(SHORT_MS);
				testBool = true;
			} catch (InterruptedException e) { }
		}, EXECUTOR).join();
		assertTrue(testBool);
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedRunAsyncTimeoutTest() {
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(SHORT_MS), () -> {
			try {
				Thread.sleep(LONG_MS);
				testBool = true;
			} catch (InterruptedException e) { }
		}, EXECUTOR).join();
		assertFalse(testBool);
	}
	
	@Test
	public void timedRunAsyncNullTimeoutTest() {
		CompletableFutureUtil.timedRunAsync(null, () -> {
			try {
				Thread.sleep(SHORT_MS);
				testBool = true;
			} catch (InterruptedException e) { }
		}, EXECUTOR).join();
		assertTrue(testBool);
	}
	
	@Test
	public void timedSupplyAsyncCompletionTest() {
		assertTrue(CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(LONG_MS), () -> {
			try {
				Thread.sleep(SHORT_MS);
			} catch (InterruptedException e) { }
			return true;
		}, EXECUTOR).join());
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedSupplyAsyncTimeoutTest() {
		assertNotEquals(CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(SHORT_MS), () -> {
			try {
				Thread.sleep(LONG_MS);
			} catch (InterruptedException e) { }
			return true;
		}, EXECUTOR).join(), true);
	}
	
	@Test
	public void timedSupplyAsyncNullTimeoutTest() {
		assertTrue(CompletableFutureUtil.timedSupplyAsync(null, () -> {
			try {
				Thread.sleep(SHORT_MS);
			} catch (InterruptedException e) { }
			return true;
		}, EXECUTOR).join());
	}
}
