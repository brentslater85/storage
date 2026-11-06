package brent.slater.storage;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import brent.slater.storage.StorageClient.Listing;
import brent.slater.storage.StorageClient.Response;
import brent.slater.storage.StorageClient.Upload;
import brent.slater.storage.api.FileResponse;

class DuplicateUploadTest extends StorageApiTest {

  private static final int PARALLEL_UPLOADS = 10;

  @Test
  void userCannotUploadTwoFilesWithTheSameFilename() {
    var userId = newUserId();
    var filename = uniqueName("report.pdf");
    var original = storage.uploaded(Upload.of(userId, filename, uniqueContent()));

    var duplicate = storage.upload(Upload.of(userId, filename, uniqueContent()));

    assertThat(duplicate.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(duplicate.problem().detail()).contains(filename);
    assertThat(storage.download(original.downloadUrl()).status()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void duplicateFilenameIsRejectedBeforeTheContentIsSent() throws IOException {
    var userId = newUserId();
    var filename = uniqueName("taken.bin");
    storage.uploaded(Upload.of(userId, filename, uniqueContent()));

    try (var socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(StorageClient.uploadRequestHead(userId, filename, 10_000_000));
      socket.getOutputStream().flush();

      var statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII)).readLine();

      assertThat(statusLine).startsWith("HTTP/1.1 409");
    }
  }

  @Test
  void userCannotUploadTheSameContentTwiceUnderDifferentFilenames() {
    var userId = newUserId();
    var content = uniqueContent();
    storage.uploaded(Upload.of(userId, uniqueName("first.txt"), content));

    var duplicate = storage.upload(Upload.of(userId, uniqueName("second.txt"), content));

    assertThat(duplicate.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(duplicate.problem().detail()).contains("same content");
  }

  @Test
  void duplicateContentIsReportedAsSuchWhateverTheUserIsCalled() {
    // MongoDB's duplicate key message repeats the key values, so the user id appears in it.
    var userId = "one_filename_per_owner-" + newUserId();
    var content = uniqueContent();
    storage.uploaded(Upload.of(userId, uniqueName("first.txt"), content));

    var duplicate = storage.upload(Upload.of(userId, uniqueName("second.txt"), content));

    assertThat(duplicate.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(duplicate.problem().detail()).contains("same content");
  }

  @Test
  void uploadThatLosesARaceForItsFilenameIsRejected() throws Exception {
    var userId = newUserId();
    var filename = uniqueName("contested.txt");

    try (var slowStorage = faults.delayNext("insert", "file_content.chunks", Duration.ofSeconds(2));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var slowUpload = executor.submit(() -> storage.upload(Upload.of(userId, filename, uniqueContent())));
      slowStorage.awaitTriggered();

      var fastUpload = storage.upload(Upload.of(userId, filename, uniqueContent()));

      assertThat(fastUpload.status()).isEqualTo(HttpStatus.CREATED);
      assertThat(slowUpload.get().status()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(slowUpload.get().problem().detail()).contains(filename);
    }
  }

  @Test
  void differentUsersCanUploadTheSameFilenameAndContent() {
    var filename = uniqueName("shared.txt");
    var content = uniqueContent();

    var alice = storage.upload(Upload.of(newUserId(), filename, content));
    var bob = storage.upload(Upload.of(newUserId(), filename, content));

    assertThat(alice.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(bob.status()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void filenameOfARejectedUploadCanBeUsedAgain() {
    var userId = newUserId();
    var content = uniqueContent();
    var retriedFilename = uniqueName("copy.txt");
    storage.uploaded(Upload.of(userId, uniqueName("original.txt"), content));
    storage.upload(Upload.of(userId, retriedFilename, content));

    var retry = storage.upload(Upload.of(userId, retriedFilename, uniqueContent()));

    assertThat(retry.status()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void parallelUploadsWithTheSameFilenameStoreExactlyOneFile() {
    var userId = newUserId();
    var filename = uniqueName("race.bin");
    var uploads = IntStream.range(0, PARALLEL_UPLOADS)
        .mapToObj(attempt -> Upload.of(userId, filename, randomBytes(1024 * 1024)))
        .toList();

    var responses = uploadAllAtOnce(uploads);

    assertExactlyOneAcceptedAndTheRestRejectedWith(responses, filename);
    assertThat(storage.list(Listing.filesOf(userId)).successBody().items())
        .singleElement()
        .satisfies(file -> assertThat(file.filename()).isEqualTo(filename));
  }

  @Test
  void parallelUploadsWithTheSameContentStoreExactlyOneFile() {
    var userId = newUserId();
    var content = randomBytes(1024 * 1024);
    var uploads = IntStream.range(0, PARALLEL_UPLOADS)
        .mapToObj(attempt -> Upload.of(userId, uniqueName("race-" + attempt + ".bin"), content))
        .toList();

    var responses = uploadAllAtOnce(uploads);

    assertExactlyOneAcceptedAndTheRestRejectedWith(responses, "same content");
    assertThat(storage.list(Listing.filesOf(userId)).successBody().items()).hasSize(1);
  }

  private static void assertExactlyOneAcceptedAndTheRestRejectedWith(
      List<Response<FileResponse>> responses, String expectedDetail) {
    assertThat(responses).extracting(Response::status).containsOnlyOnce(HttpStatus.CREATED);
    assertThat(responses)
        .filteredOn(response -> !response.status().equals(HttpStatus.CREATED))
        .allSatisfy(rejected -> {
          assertThat(rejected.status()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(rejected.problem().detail()).contains(expectedDetail);
        });
  }

  private List<Response<FileResponse>> uploadAllAtOnce(List<Upload> uploads) {
    var startingGun = new CountDownLatch(1);
    List<Future<Response<FileResponse>>> results;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      results = uploads.stream()
          .map(upload -> executor.submit(() -> {
            startingGun.await();
            return storage.upload(upload);
          }))
          .toList();
      startingGun.countDown();
    }
    return results.stream().map(Future::resultNow).toList();
  }
}
