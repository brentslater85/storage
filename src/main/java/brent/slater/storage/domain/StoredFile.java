package brent.slater.storage.domain;

import java.time.Instant;

public record StoredFile(
    String id,
    String ownerId,
    String filename,
    Visibility visibility,
    Tags tags,
    String contentType,
    long size,
    String sha256,
    String blobId,
    String downloadToken,
    Instant uploadedAt) {

  public boolean isOwnedBy(String userId) {
    return ownerId.equals(userId);
  }
}
