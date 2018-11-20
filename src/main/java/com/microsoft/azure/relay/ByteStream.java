package com.microsoft.azure.relay;

import java.util.Arrays;

public class ByteStream {
	private byte[] array;
	private int currentPosition;
	
	// returns the current position within this buffer
	public int position() {
		return this.currentPosition;
	}
	
	public int capacity() {
		return this.array.length;
	}
	
	public ByteStream(int size) {
		this.array = new byte[size];
		this.currentPosition = 0;
	}
	
	public ByteStream(byte[] bytes) {
		this.array = bytes;
		this.currentPosition = bytes.length;
	}
	
	public ByteStream(byte[] bytes, int offset, int len) {
		this.array = Arrays.copyOfRange(bytes, offset, offset + len);
		this.currentPosition = len;
	}
	
	// write a byte array to this object
	public void write(byte[] bytes) {
		this.write(bytes, 0, bytes.length);
	}
	
	// write a byte array from index "offset" and length "len" to this object
	public void write(byte[] bytes, int offset, int len) {
		
		// increase the capacity if the array won't fit
		byte[] destArray = this.array;
		if (this.currentPosition + len > this.array.length) {
			destArray = new byte[this.currentPosition + len];
			System.arraycopy(this.array, 0, destArray, 0, this.currentPosition);
		}
		System.arraycopy(bytes, offset, destArray, this.currentPosition, len);
		this.array = destArray;
		this.currentPosition = destArray.length;
	}
	
	// read this object and write  to the given buffer
	public void read(ByteStream buffer, int offset, int len) {
		
		// throws like it Stream.ReadAsync in C#
		if (buffer.position() + len > buffer.capacity()) {
			throw new IllegalArgumentException("buffer is not big enough to be read into.");
		}
		System.arraycopy(this.array, 0, buffer, buffer.position(), len);
	}
	
	public byte[] toArray() {
		return this.array;
	}

}
