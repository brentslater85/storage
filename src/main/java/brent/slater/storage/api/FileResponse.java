package brent.slater.storage.api;

import java.time.Instant;
import java.util.List;

import brent.slater.storage.domain.StoredFile;

public record FileResponse(
    String id,
    String filename,
    String visibility,
    List<String> tags,
    String contentType,
    long size,
    String sha256,
    Instant uploadDate,
    String downloadUrl) {

  static FileResponse from(StoredFile file) {
    return new FileResponse(
        file.id(),
        file.filename(),
        file.visibility().name(),
        file.tags().values(),
        file.contentType(),
        file.size(),
        file.sha256(),
        file.uploadedAt(),
        DownloadController.PATH_PREFIX + file.downloadToken());
  }
}
