package brent.slater.storage.application;

import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.springframework.stereotype.Service;

import brent.slater.storage.domain.DuplicateFileException;
import brent.slater.storage.domain.Filenames;
import brent.slater.storage.domain.NotFileOwnerException;
import brent.slater.storage.domain.StoredFile;
import brent.slater.storage.domain.StoredFileNotFoundException;
import brent.slater.storage.domain.UserIds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

  private static final int CONTENT_TYPE_SAMPLE_BYTES = 64 * 1024;
  private static final int DOWNLOAD_TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final FileCatalogue catalogue;
  private final BlobStore blobs;
  private final ContentTypeDetector contentTypes;
  private final Clock clock;

  public StoredFile upload(UploadCommand command, InputStream content) {
    requireUnusedFilename(command);
    var inspected = new InspectingInputStream(content, CONTENT_TYPE_SAMPLE_BYTES);
    var blobId = blobs.store(command.filename(), inspected);
    try {
      return catalogue.add(command, storedContent(blobId, inspected, command), newDownloadToken(), uploadTime());
    } catch (RuntimeException failure) {
      discard(blobId);
      throw failure;
    }
  }

  public StoredFile getFileByDownloadToken(String downloadToken) {
    return catalogue.findByDownloadToken(downloadToken).orElseThrow(StoredFileNotFoundException::new);
  }

  public Download download(String downloadToken) {
    var file = getFileByDownloadToken(downloadToken);
    return blobs.open(file.blobId())
        .map(content -> new Download(file, content))
        .orElseThrow(StoredFileNotFoundException::new);
  }

  public FilePage list(ListFilesQuery query) {
    return catalogue.list(query);
  }

  public StoredFile rename(String userId, String fileId, String newFilename) {
    Filenames.validate(newFilename);
    var file = ownedFile(userId, fileId);
    return catalogue.rename(file.id(), newFilename);
  }

  public void delete(String userId, String fileId) {
    var file = ownedFile(userId, fileId);
    // Content first: if either step fails the file is still listed, and retrying completes the delete.
    blobs.delete(file.blobId());
    catalogue.delete(file.id());
  }

  private void discard(String blobId) {
    try {
      blobs.delete(blobId);
    } catch (RuntimeException cleanupFailure) {
      log.warn("Could not remove content {} of a rejected upload", blobId, cleanupFailure);
    }
  }

  private StoredFile ownedFile(String userId, String fileId) {
    UserIds.validate(userId);
    var file = catalogue.findById(fileId).orElseThrow(StoredFileNotFoundException::new);
    if (!file.isOwnedBy(userId)) {
      throw new NotFileOwnerException();
    }
    return file;
  }

  private void requireUnusedFilename(UploadCommand command) {
    if (catalogue.hasFilename(command.ownerId(), command.filename())) {
      throw DuplicateFileException.sameFilename(command.filename());
    }
  }

  private StoredContent storedContent(String blobId, InspectingInputStream inspected, UploadCommand command) {
    return new StoredContent(blobId, inspected.bytesRead(), inspected.sha256Hex(), contentTypeOf(command, inspected));
  }

  private String contentTypeOf(UploadCommand command, InspectingInputStream inspected) {
    return command.declaredContentType()
        .orElseGet(() -> contentTypes.detect(inspected.leadingBytes(), command.filename()));
  }

  private Instant uploadTime() {
    // MongoDB keeps milliseconds, so finer precision would make the upload response disagree with later reads.
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }

  private static String newDownloadToken() {
    var bytes = new byte[DOWNLOAD_TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
