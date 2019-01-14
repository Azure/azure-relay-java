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
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(4), () -> {
			try {
				Thread.sleep(2);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertTrue(testBool);
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedRunAsyncTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedRunAsync(Duration.ofMillis(4), () -> {
			try {
				Thread.sleep(8);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertFalse(testBool);
	}
	
	@Test
	public void timedRunAsyncNullTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedRunAsync(null, () -> {
			try {
				Thread.sleep(2);
				testBool = true;
			} catch (InterruptedException e) { }
		}).join();
		assertTrue(testBool);
	}
	
	@Test
	public void timedSupplyAsyncCompletionTest() throws CompletionException {
		CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(4), () -> {
			try {
				Thread.sleep(2);
				testBool = true;
			} catch (InterruptedException e) { }
			return null;
		}).join();
		assertTrue(testBool);
	}
	
	@Test (expected = java.util.concurrent.CompletionException.class)
	public void timedSupplyAsyncTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedSupplyAsync(Duration.ofMillis(4), () -> {
			try {
				Thread.sleep(8);
				testBool = true;
			} catch (InterruptedException e) { }
			return null;
		}).join();
		assertFalse(testBool);
	}
	
	@Test
	public void timedSupplyAsyncNullTimeoutTest() throws CompletionException {
		CompletableFutureUtil.timedSupplyAsync(null, () -> {
			try {
				Thread.sleep(6);
				testBool = true;
			} catch (InterruptedException e) { }
			return null;
		}).join();
		assertTrue(testBool);
	}
}
