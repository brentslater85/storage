package brent.slater.storage;

import static org.assertj.core.api.Assertions.assertThat;

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

class DeleteFileTest extends StorageApiTest {

  @Test
  void ownerCanDeleteTheirFile() {
    var userId = newUserId();
    var file = storage.uploaded(Upload.of(userId, uniqueName("old.txt"), uniqueContent()));

    var response = storage.delete(userId, file.id());

    assertThat(response.status()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(storage.download(file.downloadUrl()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(storage.list(Listing.filesOf(userId)).successBody().items()).isEmpty();
  }

  @Test
  void userCannotDeleteAFileThatBelongsToSomeoneElse() {
    var content = uniqueContent();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("precious.txt"), content).publicly());

    var response = storage.delete(newUserId(), file.id());

    assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(storage.download(file.downloadUrl()).content()).isEqualTo(content);
  }

  @Test
  void deletingRequiresANonBlankUser() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()));

    var response = storage.delete("  ", file.id());

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.problem().detail()).contains("user id is required");
  }

  @Test
  void deletingAnUnknownFileIsNotFound() {
    var response = storage.delete(newUserId(), "no-such-file");

    assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void deletingTheSameFileSeveralTimesAtOnceNeverFails() {
    var userId = newUserId();
    var file = storage.uploaded(Upload.of(userId, uniqueName("double-click.txt"), uniqueContent()));
    var startingGun = new CountDownLatch(1);

    List<Future<Response<Void>>> deletes;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      deletes = IntStream.range(0, 10)
          .mapToObj(attempt -> executor.submit(() -> {
            startingGun.await();
            return storage.delete(userId, file.id());
          }))
          .toList();
      startingGun.countDown();
    }

    assertThat(deletes).extracting(Future::resultNow).extracting(Response::status)
        .contains(HttpStatus.NO_CONTENT)
        .isSubsetOf(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
  }

  @Test
  void deletedFileCanBeUploadedAgain() {
    var userId = newUserId();
    var filename = uniqueName("again.txt");
    var content = uniqueContent();
    var file = storage.uploaded(Upload.of(userId, filename, content));
    storage.delete(userId, file.id());

    var reupload = storage.upload(Upload.of(userId, filename, content));

    assertThat(reupload.status()).isEqualTo(HttpStatus.CREATED);
  }
}
