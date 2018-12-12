package com.microsoft.azure.relay;

import static org.junit.Assert.*;

import java.util.concurrent.CompletableFuture;

import org.junit.*;

public class InputQueueTests {
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
}
