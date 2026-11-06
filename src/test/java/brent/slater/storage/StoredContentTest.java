package brent.slater.storage;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import brent.slater.storage.StorageClient.Listing;
import brent.slater.storage.StorageClient.Upload;
import com.mongodb.client.gridfs.GridFSBucket;

class StoredContentTest extends StorageApiTest {

  private static final String CONTENT_FILES = "file_content.files";
  private static final String CONTENT_CHUNKS = "file_content.chunks";
  private static final int SEVERAL_CHUNKS = 1024 * 1024;

  @Autowired
  GridFSBucket storedContent;

  @Test
  void deletingAFileRemovesItsContentFromStorage() {
    var userId = newUserId();
    var filename = uniqueName("bin.txt");
    var file = storage.uploaded(Upload.of(userId, filename, randomBytes(SEVERAL_CHUNKS)));

    storage.delete(userId, file.id());

    assertNothingStoredFor(filename);
  }

  @Test
  void rejectedDuplicateContentLeavesNothingStored() {
    var userId = newUserId();
    var content = randomBytes(SEVERAL_CHUNKS);
    var duplicateFilename = uniqueName("duplicate.txt");
    storage.uploaded(Upload.of(userId, uniqueName("original.txt"), content));

    storage.upload(Upload.of(userId, duplicateFilename, content));

    assertNothingStoredFor(duplicateFilename);
  }

  @Test
  void uploadAbandonedByTheClientFreesItsFilenameAndLeavesNothingStored() throws Exception {
    var userId = newUserId();
    var filename = uniqueName("interrupted.bin");

    startUploadThenDisconnect(userId, filename);

    assertThat(retryUploadUntilAccepted(Upload.of(userId, filename, uniqueContent()))).isEqualTo(HttpStatus.CREATED);
    assertThat(storedContentNamed(filename)).isEqualTo(1);
    eventually(() -> assertThat(orphanedChunks()).isEmpty());
  }

  @Test
  void uploadThatFailsWhileStoringContentLeavesNothingStored() {
    var filename = uniqueName("storage-failure.bin");

    try (var fault = faults.failAfter(1, "insert", CONTENT_CHUNKS)) {
      var response = storage.upload(Upload.of(newUserId(), filename, randomBytes(SEVERAL_CHUNKS)));

      assertThat(response.status().is5xxServerError()).isTrue();
    }
    assertNothingStoredFor(filename);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void duplicateIsReportedEvenWhenItsContentCannotBeRemovedAndTheLeftoverIsLogged(CapturedOutput log) {
    var userId = newUserId();
    var content = uniqueContent();
    var duplicateFilename = uniqueName("duplicate.txt");
    storage.uploaded(Upload.of(userId, uniqueName("original.txt"), content));

    try (var fault = faults.failEvery("delete", CONTENT_FILES)) {
      var duplicate = storage.upload(Upload.of(userId, duplicateFilename, content));

      assertThat(duplicate.status()).isEqualTo(HttpStatus.CONFLICT);
    }
    var leftover = storedContent.find(eq("filename", duplicateFilename)).first();
    assertThat(log.getOut()).contains("Could not remove content " + leftover.getObjectId().toHexString());
  }

  @Test
  void fileWhoseContentHasGoneCannotBeDownloaded() {
    var filename = uniqueName("vanished.txt");
    var file = storage.uploaded(Upload.of(newUserId(), filename, uniqueContent()));
    removeStoredContentNamed(filename);

    var download = storage.download(file.downloadUrl());

    assertThat(download.status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void fileWhoseContentHasGoneCanStillBeDeleted() {
    var userId = newUserId();
    var filename = uniqueName("vanished.txt");
    var file = storage.uploaded(Upload.of(userId, filename, uniqueContent()));
    removeStoredContentNamed(filename);

    var response = storage.delete(userId, file.id());

    assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(storage.list(Listing.filesOf(userId)).successBody().items()).isEmpty();
  }

  @Test
  void deleteThatFailsPartWayCanBeRetried() {
    var userId = newUserId();
    var filename = uniqueName("stubborn.txt");
    var file = storage.uploaded(Upload.of(userId, filename, randomBytes(SEVERAL_CHUNKS)));

    try (var fault = faults.failEvery("delete", CONTENT_FILES)) {
      assertThat(storage.delete(userId, file.id()).status().is5xxServerError()).isTrue();
    }
    var retry = storage.delete(userId, file.id());

    assertThat(retry.status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(storage.download(file.downloadUrl()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertNothingStoredFor(filename);
  }

  private void startUploadThenDisconnect(String userId, String filename) throws IOException {
    try (var socket = new Socket("localhost", port)) {
      var output = socket.getOutputStream();
      output.write(StorageClient.uploadRequestHead(userId, filename, 10_000_000));
      output.write(new byte[SEVERAL_CHUNKS]);
      output.flush();
    }
  }

  private HttpStatusCode retryUploadUntilAccepted(Upload upload) throws InterruptedException {
    var deadline = Instant.now().plus(Duration.ofSeconds(10));
    var statuses = new ArrayList<HttpStatusCode>();
    while (Instant.now().isBefore(deadline)) {
      var status = storage.upload(upload).status();
      if (status.equals(HttpStatus.CREATED)) {
        return status;
      }
      statuses.add(status);
      Thread.sleep(100);
    }
    throw new AssertionError("Upload was never accepted, responses were " + statuses);
  }

  private void assertNothingStoredFor(String filename) {
    assertThat(storedContentNamed(filename)).isZero();
    eventually(() -> assertThat(orphanedChunks()).isEmpty());
  }

  private long storedContentNamed(String filename) {
    return storedContent.find(eq("filename", filename)).into(new ArrayList<>()).size();
  }

  private void removeStoredContentNamed(String filename) {
    storedContent.find(eq("filename", filename)).forEach(content -> storedContent.delete(content.getObjectId()));
  }

  private Set<ObjectId> orphanedChunks() {
    var chunkOwners = mongo.getCollection(CONTENT_CHUNKS).distinct("files_id", ObjectId.class).into(new HashSet<>());
    var storedFiles = mongo.getCollection(CONTENT_FILES).distinct("_id", ObjectId.class).into(new HashSet<>());
    chunkOwners.removeAll(storedFiles);
    return chunkOwners;
  }

  private static void eventually(Runnable assertion) {
    var deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (true) {
      try {
        assertion.run();
        return;
      } catch (AssertionError notYet) {
        if (Instant.now().isAfter(deadline)) {
          throw notYet;
        }
        LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
      }
    }
  }
}
