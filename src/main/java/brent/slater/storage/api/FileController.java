package brent.slater.storage.api;

import static java.util.function.Predicate.not;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import brent.slater.storage.application.FilePage;
import brent.slater.storage.application.FileScope;
import brent.slater.storage.application.FileService;
import brent.slater.storage.application.ListFilesQuery;
import brent.slater.storage.application.SortDirection;
import brent.slater.storage.application.SortField;
import brent.slater.storage.application.UploadCommand;
import brent.slater.storage.domain.Tags;
import brent.slater.storage.domain.Visibility;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
class FileController {

  static final String USER_HEADER = "X-User-Id";

  private final FileService files;

  record RenameRequest(String filename) {
  }

  record ListingParameters(String tag, String sort, String direction, int page, int size) {

    ListFilesQuery toQuery(FileScope scope) {
      return new ListFilesQuery(
          scope,
          Optional.ofNullable(tag),
          SortField.fromApiName(sort),
          SortDirection.fromApiName(direction),
          page,
          size);
    }
  }

  // Rejected while choosing the handler: reading any request parameter makes Tomcat consume a form-encoded body.
  @PostMapping(consumes = "!" + MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  FileResponse upload(
      @RequestHeader(USER_HEADER) String userId,
      @RequestParam String filename,
      @RequestParam(defaultValue = "PRIVATE") Visibility visibility,
      @RequestParam(required = false) List<String> tags,
      HttpServletRequest request) throws IOException {
    var command = new UploadCommand(
        userId,
        filename,
        visibility,
        Tags.chosen(splitAtCommas(Optional.ofNullable(tags).orElse(List.of()))),
        declaredContentType(request));
    return FileResponse.from(files.upload(command, request.getInputStream()));
  }

  @GetMapping("/public")
  FilePageResponse listPublicFiles(
      @RequestParam(required = false) String tag,
      @RequestParam(defaultValue = "uploadDate") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var parameters = new ListingParameters(tag, sort, direction, page, size);
    return toResponse(files.list(parameters.toQuery(new FileScope.PublicFiles())));
  }

  @GetMapping("/mine")
  FilePageResponse listMyFiles(
      @RequestHeader(USER_HEADER) String userId,
      @RequestParam(required = false) String tag,
      @RequestParam(defaultValue = "uploadDate") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var parameters = new ListingParameters(tag, sort, direction, page, size);
    return toResponse(files.list(parameters.toQuery(new FileScope.OwnedBy(userId))));
  }

  @GetMapping("/visible")
  FilePageResponse listFilesVisibleToMe(
      @RequestHeader(USER_HEADER) String userId,
      @RequestParam(required = false) String tag,
      @RequestParam(defaultValue = "uploadDate") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var parameters = new ListingParameters(tag, sort, direction, page, size);
    return toResponse(files.list(parameters.toQuery(new FileScope.VisibleTo(userId))));
  }

  @PatchMapping("/{fileId}")
  FileResponse rename(
      @RequestHeader(USER_HEADER) String userId,
      @PathVariable String fileId,
      @RequestBody RenameRequest rename) {
    return FileResponse.from(files.rename(userId, fileId, rename.filename()));
  }

  @DeleteMapping("/{fileId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@RequestHeader(USER_HEADER) String userId, @PathVariable String fileId) {
    files.delete(userId, fileId);
  }

  private static FilePageResponse toResponse(FilePage page) {
    return new FilePageResponse(
        page.files().stream().map(FileResponse::from).toList(),
        page.page(),
        page.size(),
        page.totalItems(),
        page.totalPages());
  }

  private static List<String> splitAtCommas(List<String> tags) {
    return tags.stream().flatMap(tag -> Arrays.stream(tag.split(","))).toList();
  }

  private static Optional<String> declaredContentType(HttpServletRequest request) {
    return Optional.ofNullable(request.getContentType())
        .map(MediaType::parseMediaType)
        .filter(MediaType::isConcrete)
        .filter(not(MediaType.APPLICATION_OCTET_STREAM::equalsTypeAndSubtype))
        .map(MediaType::toString);
  }
}
