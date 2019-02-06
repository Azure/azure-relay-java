package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.*;

public class InputQueueTest {
	private static InputQueue<Integer> queue;
	private static Integer num1 = 11;
	private static Integer num2 = 22;
	private static Integer num3 = 33;
	
	@Before
	public void init() {
		queue = new InputQueue<Integer>();
	}
	
	@Test
	public void popEmptyQueueTest() {
		CompletableFuture<Integer> num = queue.dequeueAsync();
		assertFalse(num.isDone());
	}
	
	@Test
	public void singlePushThenPopTest() {
		queue.enqueueAndDispatch(num1);
		CompletableFuture<Integer> num = queue.dequeueAsync();
		assertEquals(num1, num.join());
	}
	
	@Test 
	public void singlePopThenPushTest() {
		CompletableFuture<Integer> num = queue.dequeueAsync();
		queue.enqueueAndDispatch(num1);
		assertEquals(num1, num.join());
	}
	
	@Test
	public void multiPushThenPopTest() {
		queue.enqueueAndDispatch(num1);
		queue.enqueueAndDispatch(num2);
		queue.enqueueAndDispatch(num3);
		
		CompletableFuture<Integer> numRes1 = queue.dequeueAsync();
		CompletableFuture<Integer> numRes2 = queue.dequeueAsync();
		CompletableFuture<Integer> numRes3 = queue.dequeueAsync();
		
		assertEquals(num1, numRes1.join());
		assertEquals(num2, numRes2.join());
		assertEquals(num3, numRes3.join());
	}
	
	@Test
	public void multiPopThenPushTest() {
		CompletableFuture<Integer> numRes1 = queue.dequeueAsync();
		CompletableFuture<Integer> numRes2 = queue.dequeueAsync();
		CompletableFuture<Integer> numRes3 = queue.dequeueAsync();
		
		queue.enqueueAndDispatch(num1);
		queue.enqueueAndDispatch(num2);
		queue.enqueueAndDispatch(num3);
		
		assertEquals(num1, numRes1.join());
		assertEquals(num2, numRes2.join());
		assertEquals(num3, numRes3.join());
	}
	
	@Test
	public void mixedPushAndPopTest() {
		CompletableFuture<Integer> numRes1 = queue.dequeueAsync();
		CompletableFuture<Integer> numRes2 = queue.dequeueAsync();
		queue.enqueueAndDispatch(num1);
		CompletableFuture<Integer> numRes3 = queue.dequeueAsync();
		queue.enqueueAndDispatch(num2);
		queue.enqueueAndDispatch(num3);
		
		assertEquals(num1, numRes1.join());
		assertEquals(num2, numRes2.join());
		assertEquals(num3, numRes3.join());
	}
	
	@Test
	public void shutdownTest() {
		CompletableFuture<Integer> num1 = queue.dequeueAsync();
		CompletableFuture<Integer> num2 = queue.dequeueAsync();
		queue.shutdown();
		assertNull(num1.join());
		assertNull(num2.join());
	}
	
	@Test 
	public void shutdownWithExceptionTest() {
		CompletableFuture<Integer> num1 = queue.dequeueAsync();
		queue.shutdown(() -> new RuntimeException());
		try {
			assertNull(num1.join());
		} catch (Exception e) {
			assertTrue("The exception supposed to be thrown from InputQueue.shutdown() did not work properly", e instanceof RuntimeException);
		}
	}
	
	@Test
	public void timeoutDequeueTest() throws Throwable {
		queue.enqueueAndDispatch(num1);
		CompletableFuture<Integer> future1 = queue.dequeueAsync();
		CompletableFuture<Integer> future2 = queue.dequeueAsync(Duration.ofMillis(10));
		CompletableFuture<Integer> future3 = queue.dequeueAsync();
		assertTrue("future1 should be done without waiting", future1.isDone());
		assertFalse("future2 should not be done", future2.isDone());
		assertFalse("future3 should not be done", future3.isDone());

		try {
			Thread.sleep(20);
			queue.enqueueAndDispatch(num1);
			future2.get();
			fail("future2.get() should have thrown.");
		} catch (ExecutionException e) {
			assertEquals("Cause should be TimeoutException", e.getCause().getClass(), TimeoutException.class);
		}
		assertEquals("future3 should be completed with the value of num", num1, future3.get());
	}
	
	@Test
	public void ontimeDequeueTest() throws Throwable {
		CompletableFuture<Integer> timeoutDequeueTask = queue.dequeueAsync(Duration.ofMillis(100));
		Thread.sleep(50);
		queue.enqueueAndDispatch(num1);
		try {
			assertEquals("Dequeue did not return the expected result in time.", timeoutDequeueTask.join(), num1);
		}
		catch (Exception e) {
			fail("Dequeue threw exception when the operation is within time limit and it shouldn't have thrown.");
		}
	}
}
