package brent.slater.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import brent.slater.storage.StorageClient.Listing;
import brent.slater.storage.StorageClient.Upload;
import brent.slater.storage.api.FileResponse;

class RenameFileTest extends StorageApiTest {

  @Test
  void renamingKeepsTheContentAndDownloadLinkWithoutReuploading() {
    var userId = newUserId();
    var content = uniqueContent();
    var original = storage.uploaded(Upload.of(userId, uniqueName("draft.txt"), content));
    var newFilename = uniqueName("final.txt");

    var renamed = storage.rename(userId, original.id(), newFilename);

    assertThat(renamed.status()).isEqualTo(HttpStatus.OK);
    assertThat(renamed.body().filename()).isEqualTo(newFilename);
    assertThat(renamed.body().downloadUrl()).isEqualTo(original.downloadUrl());
    assertThat(renamed.body().sha256()).isEqualTo(original.sha256());
    var download = storage.download(original.downloadUrl());
    assertThat(download.content()).isEqualTo(content);
    assertThat(download.headers().getContentDisposition().getFilename()).isEqualTo(newFilename);
  }

  @Test
  void renamedFileIsListedUnderItsNewName() {
    var userId = newUserId();
    var original = storage.uploaded(Upload.of(userId, uniqueName("draft.txt"), uniqueContent()));
    var newFilename = uniqueName("final.txt");

    storage.rename(userId, original.id(), newFilename);

    assertThat(storage.list(Listing.filesOf(userId)).successBody().items())
        .extracting(FileResponse::filename)
        .containsExactly(newFilename);
  }

  @Test
  void cannotRenameToAFilenameTheUserAlreadyHas() {
    var userId = newUserId();
    var taken = storage.uploaded(Upload.of(userId, uniqueName("taken.txt"), uniqueContent()));
    var other = storage.uploaded(Upload.of(userId, uniqueName("other.txt"), uniqueContent()));

    var response = storage.rename(userId, other.id(), taken.filename());

    assertThat(response.status()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void previousFilenameIsFreeAfterRenaming() {
    var userId = newUserId();
    var filename = uniqueName("report.txt");
    var original = storage.uploaded(Upload.of(userId, filename, uniqueContent()));
    storage.rename(userId, original.id(), uniqueName("report-v1.txt"));

    var reupload = storage.upload(Upload.of(userId, filename, uniqueContent()));

    assertThat(reupload.status()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void onlyTheOwnerCanRenameAFile() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("mine.txt"), uniqueContent()).publicly());

    var response = storage.rename(newUserId(), file.id(), uniqueName("stolen.txt"));

    assertThat(response.status()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void renamingRequiresANonBlankUser() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()));

    var response = storage.rename("  ", file.id(), uniqueName("b.txt"));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.problem().detail()).contains("user id is required");
  }

  @Test
  void renamingAnUnknownFileIsNotFound() {
    var response = storage.rename(newUserId(), "no-such-file", uniqueName("a.txt"));

    assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void cannotRenameToABlankFilename() {
    var userId = newUserId();
    var file = storage.uploaded(Upload.of(userId, uniqueName("a.txt"), uniqueContent()));

    var response = storage.rename(userId, file.id(), " ");

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
