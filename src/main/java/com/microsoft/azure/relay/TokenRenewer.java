package com.microsoft.azure.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

class TokenRenewer {
	private final HybridConnectionListener listener;
	private final String appliesTo;
	private final Duration tokenValidFor;
	private final Lock scheduleLock = new ReentrantLock();
	private Timer renewTimer;
	private TimerTask renewTimerTask;
	private Consumer<SecurityToken> onTokenRenewed;

	protected TokenRenewer(HybridConnectionListener listener, String appliesTo, Duration tokenValidFor) {
		this.listener = listener;
		this.appliesTo = appliesTo;
		this.tokenValidFor = tokenValidFor;
	}

	Consumer<SecurityToken> getOnTokenRenewed() {
		return this.onTokenRenewed;
	}
	
	void setOnTokenRenewed(Consumer<SecurityToken> onTokenRenewed) {
		this.onTokenRenewed = onTokenRenewed;
	}

	protected CompletableFuture<SecurityToken> getTokenAsync() {
		return this.getTokenAsync(false);
	}

	private CompletableFuture<SecurityToken> getTokenAsync(boolean raiseTokenRenewedEvent) {
	    RelayLogger.logEvent("getTokenStart", this.listener);

		return this.listener.getTokenProvider()
			.getTokenAsync(this.appliesTo, this.tokenValidFor)
			.thenApply((token) -> {
				RelayLogger.logEvent("getTokenStop", this.listener, token.getExpiresAtUtc().toString());

				if (raiseTokenRenewedEvent && this.onTokenRenewed != null) {
					this.onTokenRenewed.accept(token);
				}
				this.scheduleRenewTimer(token);
				return token;
			});
	}

	protected void close() {
	    if (this.renewTimer != null) {
	        this.renewTimer.cancel();
	        this.renewTimer.purge();
	    }
	}

	void onRenewTimer() {
		try {
			this.getTokenAsync(true);
		} catch (Exception exception) {
			RelayLogger.handledExceptionAsWarning(exception, this);
		}
	}

	private void scheduleRenewTimer(SecurityToken token) {
	    if (this.renewTimer == null) {
	        this.renewTimer = new Timer();    
	    }
	    
	    if (scheduleLock.tryLock()) {
	        try {
	            Duration interval = Duration.between(Instant.now(), token.getExpiresAtUtc());
	            if (interval.isNegative()) {
	                RelayLogger.logEvent("tokenRenewNegativeDuration", this.listener);
	                return;
	            }

	            // TokenProvider won't return a token which is within 5min of expiring so we don't have to pad here.
	            interval = interval.compareTo(RelayConstants.CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL) < 0 ? 
	                    RelayConstants.CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL : interval;

	            if (this.renewTimerTask != null) {
	                this.renewTimerTask.cancel();
	            }
                this.renewTimerTask = new TimerTask() {
                    @Override
                    public void run() {
                        onRenewTimer();
                    }
                };
                this.renewTimer.schedule(this.renewTimerTask, interval.toMillis());
                RelayLogger.logEvent("tokenRenewScheduled", this.listener, interval.toString());
	        } finally {
	            scheduleLock.unlock();
	        }
	    } else {
	        // Let somebody else get token and renew, we don't need to
	    }
	}
}
