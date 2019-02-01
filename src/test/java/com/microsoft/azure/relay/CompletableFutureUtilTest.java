package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import org.junit.After;
import org.junit.Test;

public class CompletableFutureUtilTest {
	private static boolean testBool = false;
	
	@After
	public void resetBool() {
		testBool = false;
	}
	
	@Test
	public void timedRunAsyncCompletionTest() throws CompletionException {
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(30), () -> {
			try {
				Thread.sleep(15);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertTrue(testBool);
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedRunAsyncTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(15), () -> {
			try {
				Thread.sleep(30);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertFalse(testBool);
	}
	
	@Test
	public void timedRunAsyncNullTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedRunAsync(null, () -> {
			try {
				Thread.sleep(15);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertTrue(testBool);
	}
	
	@Test
	public void timedSupplyAsyncCompletionTest() throws CompletionException {
		assertTrue(CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(30), () -> {
			try {
				Thread.sleep(15);
			} catch (InterruptedException e) { }
			return true;
		}).join());
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedSupplyAsyncTimeoutTest() throws CompletionException {
		assertNotEquals(CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(15), () -> {
			try {
				Thread.sleep(30);
			} catch (InterruptedException e) { }
			return true;
		}).join(), true);
	}
	
	@Test
	public void timedSupplyAsyncNullTimeoutTest() throws CompletionException {
		assertTrue(CompletableFutureUtil.timedSupplyAsync(null, () -> {
			try {
				Thread.sleep(15);
			} catch (InterruptedException e) { }
			return true;
		}).join());
	}
}
