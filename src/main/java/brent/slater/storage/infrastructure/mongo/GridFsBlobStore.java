package brent.slater.storage.infrastructure.mongo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import brent.slater.storage.application.BlobStore;
import com.mongodb.MongoGridFSException;
import com.mongodb.client.gridfs.GridFSBucket;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class GridFsBlobStore implements BlobStore {

  // Each open download holds one batch in memory; unset, MongoDB fills batches up to 16MB.
  private static final int CHUNKS_PER_DOWNLOAD_BATCH = 4;

  private final GridFSBucket bucket;

  @Override
  public String store(String filename, InputStream content) {
    var upload = bucket.openUploadStream(filename);
    var blobId = upload.getObjectId().toHexString();
    try {
      content.transferTo(upload);
      upload.close();
      return blobId;
    } catch (IOException failure) {
      delete(blobId);
      throw new UncheckedIOException(failure);
    } catch (RuntimeException failure) {
      delete(blobId);
      throw failure;
    }
  }

  @Override
  public Optional<InputStream> open(String blobId) {
    try {
      return Optional.of(bucket.openDownloadStream(new ObjectId(blobId)).batchSize(CHUNKS_PER_DOWNLOAD_BATCH));
    } catch (MongoGridFSException missing) {
      return Optional.empty();
    }
  }

  @Override
  public void delete(String blobId) {
    try {
      bucket.delete(new ObjectId(blobId));
    } catch (MongoGridFSException alreadyGone) {
      // The driver deletes any remaining chunks before reporting that the file document was missing.
    }
  }
}
