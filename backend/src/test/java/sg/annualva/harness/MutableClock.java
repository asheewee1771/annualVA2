package sg.annualva.harness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The controllable clock: tests set an instant or advance it; the app reads it through the Clock bean. */
public final class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2027-08-02T01:00:00Z");

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }
}
