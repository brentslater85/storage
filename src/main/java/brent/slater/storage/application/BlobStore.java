package brent.slater.storage.application;

import java.io.InputStream;
import java.util.Optional;

public interface BlobStore {

  /**
   * Stores the content, leaving nothing behind if it fails part-way.
   */
  String store(String filename, InputStream content);

  Optional<InputStream> open(String blobId);

  /**
   * Succeeds when the blob is already gone, so a failed delete can be retried.
   */
  void delete(String blobId);
}
