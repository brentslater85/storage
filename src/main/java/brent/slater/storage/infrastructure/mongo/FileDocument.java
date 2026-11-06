package brent.slater.storage.infrastructure.mongo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import brent.slater.storage.domain.StoredFile;
import brent.slater.storage.domain.Tags;
import brent.slater.storage.domain.Visibility;

@Document(FileDocument.COLLECTION)
record FileDocument(
    @Id String id,
    String ownerId,
    String filename,
    Visibility visibility,
    List<String> tags,
    String contentType,
    long size,
    String sha256,
    String blobId,
    String downloadToken,
    Instant uploadedAt) {

  static final String COLLECTION = "files";

  StoredFile toStoredFile() {
    return new StoredFile(
        id,
        ownerId,
        filename,
        visibility,
        new Tags(tags),
        contentType,
        size,
        sha256,
        blobId,
        downloadToken,
        uploadedAt);
  }
}
