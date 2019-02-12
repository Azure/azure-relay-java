package com.microsoft.azure.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

class TokenRenewer {
	private final HybridConnectionListener listener;
	private final String appliesTo;
	private final Duration tokenValidFor;
	private Timer renewTimer;
	private Consumer<SecurityToken> onTokenRenewed;

	protected TokenRenewer(HybridConnectionListener listener, String appliesTo, Duration tokenValidFor) {
		this.listener = listener;
		this.appliesTo = appliesTo;
		this.tokenValidFor = tokenValidFor;
		this.renewTimer = new Timer();
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
			RelayLogger.logEvent("getTokenStart", this);

		return this.listener.getTokenProvider()
			.getTokenAsync(this.appliesTo, this.tokenValidFor)
			.thenApply((token) -> {
				RelayLogger.logEvent("getTokenStop", this, token.getExpiresAtUtc().toString());

				if (raiseTokenRenewedEvent && this.onTokenRenewed != null) {
					this.onTokenRenewed.accept(token);
				}
				this.scheduleRenewTimer(token);
				return token;
			});
	}

	protected void close() {
		this.renewTimer.cancel();
	}

	void onRenewTimer() {
		try {
			this.getTokenAsync(true);
		} catch (Exception exception) {
			RelayLogger.throwingException(exception, this);
		}
	}

	private void scheduleRenewTimer(SecurityToken token) {
		Duration interval = Duration.between(Instant.now(), token.getExpiresAtUtc());
		if (interval.isNegative()) {
			RelayLogger.logEvent("tokenRenewNegativeDuration", this);
			return;
		}

		// TokenProvider won't return a token which is within 5min of expiring so we don't have to pad here.
		interval = interval.compareTo(RelayConstants.CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL) < 0 ? 
				RelayConstants.CLIENT_MINIMUM_TOKEN_REFRESH_INTERVAL : interval;

		RelayLogger.logEvent("tokenRenewRescheduled", this, interval.toString());
		this.renewTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				onRenewTimer();
			}
		}, interval.toMillis());
	}
}
