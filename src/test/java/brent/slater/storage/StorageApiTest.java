package brent.slater.storage;

import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class StorageApiTest {

  @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:9.0")
      .withCommand("--setParameter", "enableTestCommands=1");

  static {
    MONGO.start();
  }

  @Value("${local.server.port}")
  int port;

  @Autowired
  MongoTemplate mongo;

  StorageClient storage;
  StorageFaults faults;

  @BeforeEach
  void connectClient() {
    storage = StorageClient.onPort(port);
    faults = new StorageFaults(mongo);
  }

  static String newUserId() {
    return "user-" + UUID.randomUUID();
  }

  static String uniqueName(String suffix) {
    return UUID.randomUUID() + "-" + suffix;
  }

  static byte[] uniqueContent() {
    return ("content " + UUID.randomUUID()).getBytes();
  }

  static byte[] randomBytes(int size) {
    var bytes = new byte[size];
    new Random().nextBytes(bytes);
    return bytes;
  }
}
