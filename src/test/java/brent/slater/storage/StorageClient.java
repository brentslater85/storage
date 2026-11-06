package brent.slater.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import brent.slater.storage.api.FilePageResponse;
import brent.slater.storage.api.FileResponse;

record StorageClient(RestClient rest) {

  static final String USER_HEADER = "X-User-Id";

  static StorageClient onPort(int port) {
    var rest = RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .requestFactory(new JdkClientHttpRequestFactory())
        .build();
    return new StorageClient(rest);
  }

  static byte[] uploadRequestHead(String userId, String filename, long contentLength) {
    return ("POST /files?filename=" + filename + " HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + USER_HEADER + ": " + userId + "\r\n"
        + "Content-Type: application/octet-stream\r\n"
        + "Content-Length: " + contentLength + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
  }

  record Response<T>(HttpStatusCode status, T body, Problem problem) {

    T successBody() {
      if (!status.is2xxSuccessful()) {
        throw new AssertionError("Expected success but was " + status + ": " + problem);
      }
      return body;
    }
  }

  record Problem(String title, String detail, int status) {
  }

  record Download(HttpStatusCode status, byte[] content, HttpHeaders headers) {
  }

  record Upload(
      String userId,
      String filename,
      byte[] content,
      Optional<String> visibility,
      List<String> tags,
      Optional<String> contentType) {

    static Upload of(String userId, String filename, byte[] content) {
      return new Upload(userId, filename, content, Optional.empty(), List.of(), Optional.empty());
    }

    Upload publicly() {
      return new Upload(userId, filename, content, Optional.of("PUBLIC"), tags, contentType);
    }

    Upload privately() {
      return new Upload(userId, filename, content, Optional.of("PRIVATE"), tags, contentType);
    }

    Upload withTags(String... newTags) {
      return new Upload(userId, filename, content, visibility, List.of(newTags), contentType);
    }

    Upload withContentType(String newContentType) {
      return new Upload(userId, filename, content, visibility, tags, Optional.of(newContentType));
    }

    Upload withoutUser() {
      return new Upload(null, filename, content, visibility, tags, contentType);
    }
  }

  record Listing(String path, String userId, String tag, String sort, String direction, Integer page, Integer size) {

    static Listing publicFiles() {
      return new Listing("/files/public", null, null, null, null, null, null);
    }

    static Listing visibleTo(String userId) {
      return new Listing("/files/visible", userId, null, null, null, null, null);
    }

    static Listing filesOf(String userId) {
      return new Listing("/files/mine", userId, null, null, null, null, null);
    }

    Listing taggedWith(String newTag) {
      return new Listing(path, userId, newTag, sort, direction, page, size);
    }

    Listing sortedBy(String newSort, String newDirection) {
      return new Listing(path, userId, tag, newSort, newDirection, page, size);
    }

    Listing page(int newPage, int newSize) {
      return new Listing(path, userId, tag, sort, direction, newPage, newSize);
    }
  }

  Response<FileResponse> upload(Upload upload) {
    return rest.post()
        .uri(builder -> uploadUri(builder, upload))
        .headers(headers -> {
          Optional.ofNullable(upload.userId()).ifPresent(userId -> headers.set(USER_HEADER, userId));
          upload.contentType().ifPresent(contentType -> headers.set(HttpHeaders.CONTENT_TYPE, contentType));
        })
        .body(upload.content())
        .exchange((request, response) -> read(response, FileResponse.class));
  }

  FileResponse uploaded(Upload upload) {
    return upload(upload).successBody();
  }

  Download download(String downloadUrl) {
    return rest.get()
        .uri(downloadUrl)
        .exchange((request, response) ->
            new Download(response.getStatusCode(), response.getBody().readAllBytes(), response.getHeaders()));
  }

  Response<FilePageResponse> list(Listing listing) {
    return rest.get()
        .uri(builder -> {
          builder.path(listing.path());
          Optional.ofNullable(listing.tag()).ifPresent(tag -> builder.queryParam("tag", tag));
          Optional.ofNullable(listing.sort()).ifPresent(sort -> builder.queryParam("sort", sort));
          Optional.ofNullable(listing.direction()).ifPresent(direction -> builder.queryParam("direction", direction));
          Optional.ofNullable(listing.page()).ifPresent(page -> builder.queryParam("page", page));
          Optional.ofNullable(listing.size()).ifPresent(size -> builder.queryParam("size", size));
          return builder.build();
        })
        .headers(headers -> Optional.ofNullable(listing.userId()).ifPresent(id -> headers.set(USER_HEADER, id)))
        .exchange((request, response) -> read(response, FilePageResponse.class));
  }

  Response<FileResponse> rename(String userId, String fileId, String newFilename) {
    return rest.patch()
        .uri("/files/{id}", fileId)
        .header(USER_HEADER, userId)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RenameBody(newFilename))
        .exchange((request, response) -> read(response, FileResponse.class));
  }

  Response<Void> delete(String userId, String fileId) {
    return rest.delete()
        .uri("/files/{id}", fileId)
        .header(USER_HEADER, userId)
        .exchange((request, response) -> read(response, Void.class));
  }

  private record RenameBody(String filename) {
  }

  private static URI uploadUri(UriBuilder builder, Upload upload) {
    builder.path("/files").queryParam("filename", upload.filename());
    upload.visibility().ifPresent(visibility -> builder.queryParam("visibility", visibility));
    upload.tags().forEach(tag -> builder.queryParam("tags", tag));
    return builder.build();
  }

  private static <T> Response<T> read(
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
      Class<T> type) throws IOException {
    var status = response.getStatusCode();
    if (status.is2xxSuccessful()) {
      return new Response<>(status, type == Void.class ? null : response.bodyTo(type), null);
    }
    return new Response<>(status, null, response.bodyTo(Problem.class));
  }
}
