package com.microsoft.azure.relay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.websocket.Session;


// Equivalent of HybridConnectionStream
public abstract class HybridConnectionSession implements Session {
    private String cachedToString;
    private WriteMode writeMode = WriteMode.BINARY; // default option is binary
	private TrackingContext trackingContext;
    
    WriteMode getWriteMode() {
    	return this.writeMode;
    }
    
	public void setWriteMode(WriteMode writeMode) {
		this.writeMode = writeMode;
	}
	
    public TrackingContext getTrackingContext() {
		return trackingContext;
	}

    HybridConnectionSession(TrackingContext trackingContext) {
        this.trackingContext = trackingContext;
    }

    // <summary>
    // Initiates a graceful close process by shutting down sending through this 
    // <see cref="HybridConnectionStream"/>. To disconnect cleanly and asynchronously, call Shutdown,
    // wait for Read/ReadAsync to complete with a 0 byte read, then finally call Stream.Close();
    // </summary>
    public void shutdown() {
    	try {
			this.shutdownAsync().get(this.getMaxIdleTimeout(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException|ExecutionException|TimeoutException e) {
			// TODO: trace
			throw new RuntimeException("shutdown failed: " + e.getMessage());
		}
    }

    // <summary>
    // Initiates a graceful close process by shutting down sending through this 
    // <see cref="HybridConnectionStream"/>. To disconnect cleanly and asynchronously, call ShutdownAsync,
    // wait for Read/ReadAsync to complete with a 0 byte read, then finally call Stream.CloseAsync();
    // </summary>
    // <param name="cancellationToken">A cancellation token to observe.</param>
    public CompletableFuture<Void> shutdownAsync()
    {
    	// TODO: trace
//        RelayEventSource.Log.Info(this, "Shutting down");
        return this.onShutdownAsync();
//        RelayEventSource.Log.Info(this, "Shut down");
    }

    // <summary>
    // Returns a String that represents the current object.  Includes a TrackingId for end to end correlation.
    // </summary>
    @Override
    public String toString() {
    	
    	if (this.cachedToString == null)
    		this.cachedToString = this.getClass().getName() + "(" + this.trackingContext + ")";
    	return this.cachedToString;
    }

    // <summary>
    // Closes this <see cref="HybridConnectionStream"/> instance.
    // </summary>
    // <param name="disposing">true to release both managed and unmanaged resources; false to release only unmanaged resources.</param>
    // TODO: cancellationtoken
//    protected override void Dispose(bool disposing)
//    {
//        if (disposing)
//        {
//            using (var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(this.ReadTimeout)))
//            {
//                this.CloseAsync(cts.Token).ConfigureAwait(false).GetAwaiter().GetResult();
//            }
//        }
//
//        base.Dispose(disposing);
//    }

    // <summary>
    // Closes this <see cref="HybridConnectionStream"/> instance asynchronously using a <see cref="CancellationToken"/>.
    // </summary>
    // <param name="cancellationToken">A cancellation token to observe.</param>
    public CompletableFuture<Void> closeAsync() {
    	// TODO: trace
//            RelayEventSource.Log.ObjectClosing(this);
        return this.onCloseAsync();
//            RelayEventSource.Log.ObjectClosed(this);
    }

    // <summary>
    // Derived classes implement shutdown logic in this method.
    // </summary>
    // <param name="cancellationToken">A cancellation token to observe.</param>
    // TODO: cncellationtoken
    protected abstract CompletableFuture<Void> onShutdownAsync();

    // <summary>
    // Derived classes implement close logic in this method.
    // </summary>
    // <param name="cancellationToken">A cancellation token to observe.</param>
    // TODO: cancellationtoken
    protected abstract CompletableFuture<Void> onCloseAsync();
}
