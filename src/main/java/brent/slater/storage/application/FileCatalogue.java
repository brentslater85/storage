package brent.slater.storage.application;

import java.time.Instant;
import java.util.Optional;

import brent.slater.storage.domain.StoredFile;

public interface FileCatalogue {

  boolean hasFilename(String ownerId, String filename);

  StoredFile add(UploadCommand command, StoredContent content, String downloadToken, Instant uploadedAt);

  Optional<StoredFile> findById(String fileId);

  Optional<StoredFile> findByDownloadToken(String downloadToken);

  FilePage list(ListFilesQuery query);

  StoredFile rename(String fileId, String newFilename);

  void delete(String fileId);
}
