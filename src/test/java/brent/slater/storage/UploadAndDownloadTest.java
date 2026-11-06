package brent.slater.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import brent.slater.storage.StorageClient.Listing;
import brent.slater.storage.StorageClient.Upload;
import brent.slater.storage.api.FileResponse;

class UploadAndDownloadTest extends StorageApiTest {

  @Test
  void uploadedFileCanBeDownloadedFromItsLink() {
    var content = "quarterly numbers".getBytes();
    var filename = uniqueName("report.txt");

    var response = storage.upload(Upload.of(newUserId(), filename, content));

    assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    var download = storage.download(response.body().downloadUrl());
    assertThat(download.status()).isEqualTo(HttpStatus.OK);
    assertThat(download.content()).isEqualTo(content);
    assertThat(download.headers().getContentDisposition().getFilename()).isEqualTo(filename);
    assertThat(download.headers().getContentLength()).isEqualTo(content.length);
  }

  @Test
  void uploadReportsTheStoredFileDetails() throws Exception {
    var content = uniqueContent();
    var filename = uniqueName("notes.txt");

    var file = storage.uploaded(Upload.of(newUserId(), filename, content).publicly());

    assertThat(file.filename()).isEqualTo(filename);
    assertThat(file.visibility()).isEqualTo("PUBLIC");
    assertThat(file.size()).isEqualTo(content.length);
    assertThat(file.sha256()).isEqualTo(sha256Of(content));
    assertThat(file.uploadDate()).isNotNull();
  }

  @Test
  void filesArePrivateUnlessVisibilityIsGiven() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()));

    assertThat(file.visibility()).isEqualTo("PRIVATE");
  }

  @Test
  void privateFileCanBeDownloadedByAnyoneHoldingTheLink() {
    var content = uniqueContent();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("secret.txt"), content).privately());

    var download = storage.download(file.downloadUrl());

    assertThat(download.status()).isEqualTo(HttpStatus.OK);
    assertThat(download.content()).isEqualTo(content);
  }

  @Test
  void downloadLinkIsRelativeToTheServiceAndNotDerivedFromTheFileId() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()));

    assertThat(file.downloadUrl()).startsWith("/download/");
    var token = file.downloadUrl().substring("/download/".length());
    assertThat(token).hasSizeGreaterThanOrEqualTo(43).doesNotContain(file.id());
  }

  @Test
  void headRequestDescribesADownloadWithoutReadingItsContent() throws Exception {
    var content = uniqueContent();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.txt"), content));
    var head = HttpRequest.newBuilder(URI.create("http://localhost:" + port + file.downloadUrl())).HEAD().build();

    try (var unreadableContent = faults.failEvery("find", "file_content.chunks")) {
      var response = HttpClient.newHttpClient().send(head, BodyHandlers.discarding());

      assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
      assertThat(response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH)).hasValue(content.length);
    }
  }

  @Test
  void guessedDownloadLinkIsNotFound() {
    var download = storage.download("/download/not-a-real-token");

    assertThat(download.status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void detectsContentTypeWhenNotProvided() {
    var pdf = ("%PDF-1.7\n" + uniqueName("body")).getBytes();

    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("document"), pdf));

    assertThat(file.contentType()).isEqualTo("application/pdf");
  }

  @ParameterizedTest
  @ValueSource(strings = {"*/*", "application/*", "image/*", "application/octet-stream", "application/octet-stream; x=y"})
  void detectsContentTypeWhenTheProvidedOneIsNotSpecific(String vagueContentType) {
    var pdf = ("%PDF-1.7\n" + uniqueName("body")).getBytes();

    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("document"), pdf).withContentType(vagueContentType));

    assertThat(file.contentType()).isEqualTo("application/pdf");
  }

  @Test
  void keepsContentTypeProvidedByTheUser() {
    var file = storage.uploaded(
        Upload.of(newUserId(), uniqueName("data"), uniqueContent()).withContentType("text/csv"));

    assertThat(file.contentType()).isEqualTo("text/csv");
    assertThat(storage.download(file.downloadUrl()).headers().getContentType()).hasToString("text/csv");
  }

  @Test
  void tagsAreCaseInsensitiveSoDifferentlyCasedTagsAreTheSameTag() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.jpg"), uniqueContent())
        .withTags("Holiday", "HOLIDAY", " holiday ", "Beach"));

    assertThat(file.tags()).containsExactly("holiday", "beach");
  }

  @Test
  void tagsCanBeRepeatedAndCommaSeparatedInTheSameUpload() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("a.jpg"), uniqueContent())
        .withTags("beach,2026", "summer"));

    assertThat(file.tags()).containsExactly("beach", "2026", "summer");
  }

  @Test
  void uploadDateIsReportedTheSameWayEverywhere() {
    var userId = newUserId();
    var uploaded = storage.uploaded(Upload.of(userId, uniqueName("a.txt"), uniqueContent()));

    var listed = storage.list(Listing.filesOf(userId)).successBody().items();

    assertThat(listed).singleElement().extracting(FileResponse::uploadDate).isEqualTo(uploaded.uploadDate());
  }

  @Test
  void downloadsAreNeverReinterpretedByBrowsers() {
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("page.html"), uniqueContent())
        .withContentType("text/html"));

    var download = storage.download(file.downloadUrl());

    assertThat(download.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void fileCanHaveAtMostFiveTags() {
    var userId = newUserId();
    var filename = uniqueName("a.jpg");
    var content = uniqueContent();

    var sixTags = storage.upload(Upload.of(userId, filename, content).withTags("1", "2", "3", "4", "5", "6"));
    var fiveTags = storage.upload(Upload.of(userId, filename, content).withTags("1", "2", "3", "4", "5"));

    assertThat(sixTags.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(sixTags.problem().detail()).contains("at most 5 tags");
    assertThat(fiveTags.status()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void uploadRequiresAUser() {
    var response = storage.upload(Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()).withoutUser());

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void uploadRequiresANonBlankUser() {
    var response = storage.upload(Upload.of("  ", uniqueName("a.txt"), uniqueContent()));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.problem().detail()).contains("user id is required");
  }

  @Test
  void rejectsAContentTypeThatIsNotAMediaType() throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/files?filename=a.txt"))
        .header(StorageClient.USER_HEADER, newUserId())
        .header(HttpHeaders.CONTENT_TYPE, "not a media type")
        .POST(BodyPublishers.ofByteArray(uniqueContent()))
        .build();

    var response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
  }

  @Test
  void uploadRequiresAFilename() {
    var response = storage.upload(Upload.of(newUserId(), "  ", uniqueContent()));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void filenameCanBeUpTo255Characters() {
    var userId = newUserId();
    var longestAllowed = uniqueName("").concat("x".repeat(255)).substring(0, 255);

    var accepted = storage.upload(Upload.of(userId, longestAllowed, uniqueContent()));
    var rejected = storage.upload(Upload.of(userId, longestAllowed + "x", uniqueContent()));

    assertThat(accepted.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(rejected.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void uploadRejectsUnknownVisibility() {
    var upload = Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent());
    var response = storage.upload(new Upload(upload.userId(), upload.filename(), upload.content(),
        Optional.of("SHARED"), upload.tags(), upload.contentType()));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsFormEncodedUploadsBecauseTheServerWouldReadTheFileAsFormFields() {
    var upload = Upload.of(newUserId(), uniqueName("form.csv"), "name,amount\nalice,10\n".getBytes())
        .withContentType("application/x-www-form-urlencoded");

    var response = storage.upload(upload);

    assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(response.problem().detail()).endsWith("is not supported.");
    assertThat(storage.list(Listing.filesOf(upload.userId())).successBody().items()).isEmpty();
  }

  @Test
  void bodyDeclaredAsMultipartIsStoredExactlyAsSent() {
    var content = uniqueContent();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("envelope.bin"), content)
        .withContentType("multipart/form-data; boundary=x"));

    assertThat(storage.download(file.downloadUrl()).content()).isEqualTo(content);
  }

  static String sha256Of(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}
