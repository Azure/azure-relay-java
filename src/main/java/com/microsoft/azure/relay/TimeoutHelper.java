package com.microsoft.azure.relay;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

class TimeoutHelper {
	private Instant deadline;
	private boolean deadlineSet;
	private Duration originalTimeout;

	TimeoutHelper(Duration timeout) {
		this(timeout, false);
	}

	TimeoutHelper(Duration timeout, boolean startTimeout) {
		this.originalTimeout = timeout;
		this.deadline = Instant.MAX;
		this.deadlineSet = (timeout != null && isMaxDuration(timeout));

		if (startTimeout && !this.deadlineSet) {
			this.setDeadline();
		}
	}

	Duration getOriginalTimeout() {
		return this.originalTimeout;
	}

	static Duration fromMillis(int milliseconds) {
		if (milliseconds >= Integer.MAX_VALUE) {
			return RelayConstants.MAX_DURATION;
		} else {
			return Duration.ofMillis(milliseconds);
		}
	}

	static int toMillis(Duration timeout) {
		long millis = timeout.toMillis();
		return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
	}

	static Duration min(Duration val1, Duration val2) {
		return (val1.compareTo(val2) < 0) ? val1 : val2;
	}

	static Instant min(Instant val1, Instant val2) {
		return (val1.compareTo(val2) < 0) ? val1 : val2;
	}

	static Instant add(Instant time, Duration timeout) {
		try {
			return time.plusNanos(timeout.toNanos());
		}
		// Catch if the result is out of bounds
		catch (DateTimeException e) {
			if (timeout.compareTo(Duration.ZERO) < 0) {
				return Instant.MIN;
			} else {
				return Instant.MAX;
			}
		}
	}

	static Instant subtract(Instant time, Duration timeout) {
		try {
			return time.minusNanos(timeout.toNanos());
		}
		// Catch if the result is out of bounds
		catch (DateTimeException e) {
			if (timeout.compareTo(Duration.ZERO) < 0) {
				return Instant.MIN;
			} else {
				return Instant.MAX;
			}
		}
	}

	Duration remainingTime() {
		if (!this.deadlineSet) {
			this.setDeadline();
			return this.originalTimeout;
		} else if (this.deadline == Instant.MAX) {
			return RelayConstants.MAX_DURATION;
		} else {
			Duration remaining = Duration.between(Instant.now(), this.deadline);
			return (remaining.compareTo(Duration.ZERO) < 0) ? Duration.ZERO : remaining;
		}
	}

	Duration elapsedTime() {
		return this.originalTimeout.minus(this.remainingTime());
	}

	private void setDeadline() {
		if (!deadlineSet) {
			this.deadline = add(Instant.now(), this.originalTimeout);
			this.deadlineSet = true;
		}
	}

	static void throwIfNegativeArgument(Duration timeout) {
		throwIfNegativeArgument(timeout, "timeout");
	}

	static void throwIfNegativeArgument(Duration timeout, String argumentName) {
		if (timeout != null && timeout.isNegative()) {
			RelayLogger.throwingException(new IllegalArgumentException("timeout interval cannot be negative."), TimeoutHelper.class);
		}
	}

	private boolean isMaxDuration(Duration duration) {
		return duration.compareTo(RelayConstants.MAX_DURATION) >= 0
				|| duration.compareTo(RelayConstants.MIN_DURATION) <= 0;
	}
}
