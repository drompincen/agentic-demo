package com.example.agency.driver;

import com.example.agency.bus.Source.FileSource;
import com.example.agency.bus.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

public class EventDriver {
    private static final Logger log = LoggerFactory.getLogger(EventDriver.class);
    private final FileSource bus;

    private EventDriver(FileSource bus) { this.bus = bus; }
    public static Builder builder(FileSource bus) { return new Builder(bus); }

    public static class Builder {
        private final FileSource bus;
        private String eventName;
        private Instant timestamp;
        Builder(FileSource bus) { this.bus = bus; }
        public Builder event(String name) { this.eventName = name; return this; }
        public Builder at(Instant ts) { this.timestamp = ts; return this; }
        public Builder publish() {
            String payload = eventName+":"+timestamp;
            bus.publish(payload); log.info("Published {}", payload);
            return this;
        }
        public Builder sleep(Duration d) { try { Thread.sleep(d.toMillis()); } catch(Exception ignored){}; return this; }
    }

    public static void main(String[] args) {
        FileSource bus = new Source.FileSource("events");
        builder(bus)
//                .event("TodayEvent").at(Instant.parse("2025-05-01T09:00:00Z")).publish()
//                .sleep(Duration.ofSeconds(21))
//                .event("TodayEvent").at(Instant.parse("2025-05-15T12:00:00Z")).publish();
        .event("groomingNeeded").at(Instant.now()).publish();
    }
}
