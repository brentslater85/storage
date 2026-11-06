package brent.slater.storage;

import java.time.Duration;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Makes MongoDB itself fail or stall chosen commands, using its {@code failCommand} failpoint, so tests can see how
 * the service behaves when storage breaks or slows down part-way through a request.
 */
record StorageFaults(MongoTemplate mongo) {

  private static final int BAD_VALUE = 2;
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  record Fault(StorageFaults faults, int timesTriggeredBefore) implements AutoCloseable {

    void awaitTriggered() {
      faults.admin(new Document()
          .append("waitForFailPoint", "failCommand")
          .append("timesEntered", timesTriggeredBefore + 1)
          .append("maxTimeMS", WAIT_LIMIT.toMillis()));
    }

    @Override
    public void close() {
      faults.configure("off", new Document());
    }
  }

  Fault failEvery(String command, String collection) {
    return failAfter(0, command, collection);
  }

  Fault failAfter(int successfulCommands, String command, String collection) {
    return inject(new Document("skip", successfulCommands), command, collection,
        new Document("errorCode", BAD_VALUE));
  }

  Fault delayNext(String command, String collection, Duration delay) {
    return inject(new Document("times", 1), command, collection,
        new Document("blockConnection", true).append("blockTimeMS", delay.toMillis()));
  }

  private Fault inject(Document mode, String command, String collection, Document behaviour) {
    var configured = configure(mode, behaviour
        .append("failCommands", List.of(command))
        .append("namespace", mongo.getDb().getName() + "." + collection));
    return new Fault(this, configured.get("count", Number.class).intValue());
  }

  private Document configure(Object mode, Document data) {
    return admin(new Document()
        .append("configureFailPoint", "failCommand")
        .append("mode", mode)
        .append("data", data));
  }

  private Document admin(Document command) {
    return mongo.getMongoDatabaseFactory().getMongoDatabase("admin").runCommand(command);
  }
}
