package brent.slater.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import brent.slater.storage.StorageClient.Listing;
import brent.slater.storage.StorageClient.Upload;
import brent.slater.storage.api.FileResponse;

class ListFilesTest extends StorageApiTest {

  @Test
  void listsAllPublicFilesFromEveryUser() {
    var alicePublic = storage.uploaded(Upload.of(newUserId(), uniqueName("alice.txt"), uniqueContent()).publicly());
    var alicePrivate = storage.uploaded(Upload.of(newUserId(), uniqueName("hidden.txt"), uniqueContent()));
    var bobPublic = storage.uploaded(Upload.of(newUserId(), uniqueName("bob.txt"), uniqueContent()).publicly());

    var listed = storage.list(Listing.publicFiles().page(0, 100)).successBody().items();

    assertThat(listed).extracting(FileResponse::visibility).containsOnly("PUBLIC");
    assertThat(listed).extracting(FileResponse::id)
        .contains(alicePublic.id(), bobPublic.id())
        .doesNotContain(alicePrivate.id());
  }

  @Test
  void listsEveryFileBelongingToTheUserWhateverItsVisibility() {
    var userId = newUserId();
    var publicFile = storage.uploaded(Upload.of(userId, uniqueName("public.txt"), uniqueContent()).publicly());
    var privateFile = storage.uploaded(Upload.of(userId, uniqueName("private.txt"), uniqueContent()));
    storage.uploaded(Upload.of(newUserId(), uniqueName("someone-else.txt"), uniqueContent()).publicly());

    var listed = storage.list(Listing.filesOf(userId)).successBody().items();

    assertThat(listed).extracting(FileResponse::id).containsExactlyInAnyOrder(publicFile.id(), privateFile.id());
  }

  @Test
  void listsEveryPublicFileAndAllOfTheUsersOwnFiles() {
    var userId = newUserId();
    var otherUserId = newUserId();
    var tag = uniqueName("visible");
    var ownPublic = storage.uploaded(Upload.of(userId, uniqueName("own-public.txt"), uniqueContent())
        .publicly().withTags(tag));
    var ownPrivate = storage.uploaded(Upload.of(userId, uniqueName("own-private.txt"), uniqueContent())
        .privately().withTags(tag));
    var othersPublic = storage.uploaded(Upload.of(otherUserId, uniqueName("their-public.txt"), uniqueContent())
        .publicly().withTags(tag));
    storage.uploaded(Upload.of(otherUserId, uniqueName("their-private.txt"), uniqueContent())
        .privately().withTags(tag));

    var listed = storage.list(Listing.visibleTo(userId).taggedWith(tag)).successBody().items();

    assertThat(listed).extracting(FileResponse::id)
        .containsExactlyInAnyOrder(ownPublic.id(), ownPrivate.id(), othersPublic.id());
  }

  @Test
  void filtersByTagIgnoringCase() {
    var userId = newUserId();
    var holiday = storage.uploaded(Upload.of(userId, uniqueName("beach.jpg"), uniqueContent()).withTags("Holiday"));
    storage.uploaded(Upload.of(userId, uniqueName("payslip.pdf"), uniqueContent()).withTags("work"));

    var listed = storage.list(Listing.filesOf(userId).taggedWith("HOLIDAY")).successBody().items();

    assertThat(listed).extracting(FileResponse::id).containsExactly(holiday.id());
  }

  @Test
  void tagFilterOnlyMatchesWholeTags() {
    var userId = newUserId();
    storage.uploaded(Upload.of(userId, uniqueName("beach.jpg"), uniqueContent()).withTags("holiday"));

    var listed = storage.list(Listing.filesOf(userId).taggedWith("hol")).successBody().items();

    assertThat(listed).isEmpty();
  }

  @Test
  void filtersPublicFilesByTag() {
    var tag = "tag-" + UUID.randomUUID();
    var tagged = storage.uploaded(
        Upload.of(newUserId(), uniqueName("a.txt"), uniqueContent()).publicly().withTags(tag));
    storage.uploaded(Upload.of(newUserId(), uniqueName("b.txt"), uniqueContent()).privately().withTags(tag));

    var listed = storage.list(Listing.publicFiles().taggedWith(tag.toUpperCase())).successBody().items();

    assertThat(listed).extracting(FileResponse::id).containsExactly(tagged.id());
  }

  static Stream<Arguments> sortOrders() {
    return Stream.of(
        Arguments.of("filename", "asc", "a.txt", "b.txt", "c.txt"),
        Arguments.of("filename", "desc", "c.txt", "b.txt", "a.txt"),
        Arguments.of("size", "asc", "a.txt", "c.txt", "b.txt"),
        Arguments.of("size", "desc", "b.txt", "c.txt", "a.txt"),
        Arguments.of("contentType", "asc", "c.txt", "a.txt", "b.txt"),
        Arguments.of("tag", "asc", "c.txt", "b.txt", "a.txt"),
        Arguments.of("uploadDate", "asc", "b.txt", "a.txt", "c.txt"),
        Arguments.of("uploadDate", "desc", "c.txt", "a.txt", "b.txt"));
  }

  @ParameterizedTest(name = "sorted by {0} {1}")
  @MethodSource("sortOrders")
  void sortsFiles(String sort, String direction, String first, String second, String third) {
    var userId = newUserId();
    storage.uploaded(Upload.of(userId, "b.txt", "333".getBytes()).withContentType("text/plain").withTags("m"));
    storage.uploaded(Upload.of(userId, "a.txt", "1".getBytes()).withContentType("image/png").withTags("z"));
    storage.uploaded(Upload.of(userId, "c.txt", "22".getBytes()).withContentType("application/json").withTags("a"));

    var listed = storage.list(Listing.filesOf(userId).sortedBy(sort, direction)).successBody().items();

    assertThat(listed).extracting(FileResponse::filename).containsExactly(first, second, third);
  }

  @Test
  void newestFilesAreListedFirstByDefault() {
    var userId = newUserId();
    var older = storage.uploaded(Upload.of(userId, uniqueName("older.txt"), uniqueContent()));
    var newer = storage.uploaded(Upload.of(userId, uniqueName("newer.txt"), uniqueContent()));

    var listed = storage.list(Listing.filesOf(userId)).successBody().items();

    assertThat(listed).extracting(FileResponse::id).containsExactly(newer.id(), older.id());
  }

  @Test
  void listsTheFirstPageOfTwentyWhenNoPageIsRequested() {
    var userId = newUserId();
    IntStream.range(0, 21).forEach(file -> storage.uploaded(Upload.of(userId, uniqueName("a.txt"), uniqueContent())));

    var firstPage = storage.list(Listing.filesOf(userId)).successBody();

    assertThat(firstPage.items()).hasSize(20);
    assertThat(firstPage.page()).isZero();
    assertThat(firstPage.size()).isEqualTo(20);
    assertThat(firstPage.totalItems()).isEqualTo(21);
    assertThat(firstPage.totalPages()).isEqualTo(2);
  }

  @Test
  void dividesResultsIntoPagesOfTheRequestedSize() {
    var userId = newUserId();
    Stream.of("1", "2", "3", "4", "5")
        .forEach(name -> storage.uploaded(Upload.of(userId, name, name.getBytes())));

    var first = storage.list(Listing.filesOf(userId).sortedBy("filename", "asc").page(0, 2)).successBody();
    var last = storage.list(Listing.filesOf(userId).sortedBy("filename", "asc").page(2, 2)).successBody();

    assertThat(first.items()).extracting(FileResponse::filename).containsExactly("1", "2");
    assertThat(first.page()).isZero();
    assertThat(first.size()).isEqualTo(2);
    assertThat(first.totalItems()).isEqualTo(5);
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(last.items()).extracting(FileResponse::filename).containsExactly("5");
  }

  @ParameterizedTest(name = "page {0} of size {1}")
  @CsvSource({"67108864, 64", "1073741824, 2", "2147483647, 100"})
  void pagesFarBeyondTheLastAreEmpty(int page, int size) {
    var userId = newUserId();
    storage.uploaded(Upload.of(userId, uniqueName("only.txt"), uniqueContent()));

    var response = storage.list(Listing.filesOf(userId).page(page, size));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.body().items()).isEmpty();
    assertThat(response.body().totalItems()).isEqualTo(1);
  }

  @Test
  void rejectsSortingByAnUnsupportedField() {
    var response = storage.list(Listing.filesOf(newUserId()).sortedBy("sha256", "asc"));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.problem().detail()).contains("filename", "uploadDate", "tag", "contentType", "size");
  }

  @Test
  void rejectsAnUnknownSortDirection() {
    var response = storage.list(Listing.filesOf(newUserId()).sortedBy("filename", "sideways"));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void blankTagFilterListsEveryFile() {
    var userId = newUserId();
    storage.uploaded(Upload.of(userId, uniqueName("a.txt"), uniqueContent()).withTags("work"));
    storage.uploaded(Upload.of(userId, uniqueName("b.txt"), uniqueContent()));

    var listed = storage.list(Listing.filesOf(userId).taggedWith("  ")).successBody().items();

    assertThat(listed).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 100})
  void acceptsPageSizesFromOneToOneHundred(int size) {
    var response = storage.list(Listing.publicFiles().page(0, size));

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.body().size()).isEqualTo(size);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 101})
  void rejectsPageSizesOutsideOneToOneHundred(int size) {
    var response = storage.list(Listing.publicFiles().page(0, size));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsNegativePageNumbers() {
    var response = storage.list(Listing.publicFiles().page(-1, 10));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void filesStoredUnderLooserRulesCanStillBeListed() {
    var userId = newUserId();
    var withSixTags = storeFileDocument(userId, List.of("1", "2", "3", "4", "5", "6"));
    var withoutTags = storeFileDocument(userId, null);

    var listed = storage.list(Listing.filesOf(userId)).successBody().items();

    assertThat(listed).extracting(FileResponse::id).containsExactlyInAnyOrder(withSixTags, withoutTags);
    assertThat(listed).filteredOn(file -> file.id().equals(withSixTags)).singleElement()
        .extracting(FileResponse::tags).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(6);
    assertThat(listed).filteredOn(file -> file.id().equals(withoutTags)).singleElement()
        .extracting(FileResponse::tags).asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
  }

  @Test
  void listingYourOwnFilesRequiresAUser() {
    var response = storage.list(Listing.filesOf(null));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "  ")
  void listingTheFilesVisibleToYouRequiresAUser(String userId) {
    var response = storage.list(Listing.visibleTo(userId));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void listingYourOwnFilesRequiresANonBlankUser() {
    var response = storage.list(Listing.filesOf("  "));

    assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.problem().detail()).contains("user id is required");
  }

  private String storeFileDocument(String userId, List<String> tags) {
    var id = UUID.randomUUID().toString();
    var document = new Document()
        .append("_id", id)
        .append("ownerId", userId)
        .append("filename", uniqueName("legacy.txt"))
        .append("visibility", "PRIVATE")
        .append("contentType", "text/plain")
        .append("size", 1L)
        .append("sha256", id)
        .append("blobId", new ObjectId().toHexString())
        .append("downloadToken", id)
        .append("uploadedAt", new Date());
    Optional.ofNullable(tags).ifPresent(present -> document.append("tags", present));
    mongo.getCollection("files").insertOne(document);
    return id;
  }
}
