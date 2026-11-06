package brent.slater.storage.api;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import brent.slater.storage.application.FileService;
import brent.slater.storage.domain.StoredFile;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class DownloadController {

  static final String PATH_PREFIX = "/download/";
  private static final String DOWNLOAD_PATH = PATH_PREFIX + "{downloadToken}";

  private final FileService files;

  @GetMapping(DOWNLOAD_PATH)
  ResponseEntity<InputStreamResource> download(@PathVariable String downloadToken) {
    var download = files.download(downloadToken);
    return headersFor(download.file()).body(new InputStreamResource(download.content()));
  }

  @RequestMapping(path = DOWNLOAD_PATH, method = RequestMethod.HEAD)
  ResponseEntity<Void> downloadHeaders(@PathVariable String downloadToken) {
    return headersFor(files.getFileByDownloadToken(downloadToken)).build();
  }

  private static ResponseEntity.BodyBuilder headersFor(StoredFile file) {
    var disposition = ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .contentLength(file.size())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff");
  }
}
