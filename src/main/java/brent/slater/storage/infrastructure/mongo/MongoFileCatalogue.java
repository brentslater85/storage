package brent.slater.storage.infrastructure.mongo;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import brent.slater.storage.application.FileCatalogue;
import brent.slater.storage.application.FilePage;
import brent.slater.storage.application.FileScope;
import brent.slater.storage.application.ListFilesQuery;
import brent.slater.storage.application.SortDirection;
import brent.slater.storage.application.SortField;
import brent.slater.storage.application.StoredContent;
import brent.slater.storage.application.UploadCommand;
import brent.slater.storage.domain.DuplicateFileException;
import brent.slater.storage.domain.StoredFile;
import brent.slater.storage.domain.StoredFileNotFoundException;
import brent.slater.storage.domain.Visibility;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
class MongoFileCatalogue implements FileCatalogue {

  // MongoDB names the index before echoing the duplicated values, which can contain anything a user sent.
  private static final Pattern DUPLICATED_INDEX = Pattern.compile("index: (\\S+) dup key");

  private final MongoTemplate mongo;

  @Override
  public boolean hasFilename(String ownerId, String filename) {
    return mongo.exists(query(where("ownerId").is(ownerId).and("filename").is(filename)), FileDocument.class);
  }

  @Override
  public StoredFile add(UploadCommand command, StoredContent content, String downloadToken, Instant uploadedAt) {
    var file = new FileDocument(
        UUID.randomUUID().toString(),
        command.ownerId(),
        command.filename(),
        command.visibility(),
        command.tags().values(),
        content.contentType(),
        content.size(),
        content.sha256(),
        content.blobId(),
        downloadToken,
        uploadedAt);
    try {
      return mongo.insert(file).toStoredFile();
    } catch (DuplicateKeyException duplicate) {
      throw duplicateOf(command, duplicate);
    }
  }

  private static DuplicateFileException duplicateOf(UploadCommand command, DuplicateKeyException duplicate) {
    var index = DUPLICATED_INDEX.matcher(duplicate.getMessage());
    return index.find() && index.group(1).equals(FileIndexes.ONE_FILENAME_PER_OWNER)
        ? DuplicateFileException.sameFilename(command.filename())
        : DuplicateFileException.sameContent();
  }

  @Override
  public Optional<StoredFile> findByDownloadToken(String downloadToken) {
    return Optional.ofNullable(mongo.findOne(
            query(where("downloadToken").is(downloadToken)), FileDocument.class))
        .map(FileDocument::toStoredFile);
  }

  @Override
  public Optional<StoredFile> findById(String fileId) {
    return Optional.ofNullable(mongo.findById(fileId, FileDocument.class)).map(FileDocument::toStoredFile);
  }

  @Override
  public FilePage list(ListFilesQuery listFilesQuery) {
    var filter = query(matching(listFilesQuery));
    var total = mongo.count(filter, FileDocument.class);
    var offset = (long) listFilesQuery.page() * listFilesQuery.size();
    // Spring Data narrows skip to an int, so an unclamped offset past the end wraps round to an earlier page.
    var page = Query.of(filter)
        .with(sortOf(listFilesQuery))
        .skip(Math.min(offset, total))
        .limit(listFilesQuery.size());
    var files = mongo.find(page, FileDocument.class).stream().map(FileDocument::toStoredFile).toList();
    return new FilePage(files, listFilesQuery.page(), listFilesQuery.size(), total);
  }

  @Override
  public StoredFile rename(String fileId, String newFilename) {
    try {
      return Optional.ofNullable(mongo.findAndModify(
              query(where("_id").is(fileId)),
              Update.update("filename", newFilename),
              FindAndModifyOptions.options().returnNew(true),
              FileDocument.class))
          .map(FileDocument::toStoredFile)
          .orElseThrow(StoredFileNotFoundException::new);
    } catch (DuplicateKeyException duplicate) {
      throw DuplicateFileException.sameFilename(newFilename);
    }
  }

  @Override
  public void delete(String fileId) {
    mongo.remove(query(where("_id").is(fileId)), FileDocument.class);
  }

  private static Criteria matching(ListFilesQuery listFilesQuery) {
    var criteria = switch (listFilesQuery.scope()) {
      case FileScope.PublicFiles publicFiles -> where("visibility").is(Visibility.PUBLIC);
      case FileScope.OwnedBy owner -> where("ownerId").is(owner.ownerId());
      case FileScope.VisibleTo viewer -> new Criteria().orOperator(
          where("visibility").is(Visibility.PUBLIC), where("ownerId").is(viewer.userId()));
    };
    listFilesQuery.tag().ifPresent(tag -> criteria.and("tags").is(tag));
    return criteria;
  }

  private static Sort sortOf(ListFilesQuery listFilesQuery) {
    var direction = listFilesQuery.direction() == SortDirection.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
    // Without a unique tie-breaker, files with equal sort values could appear on two pages or none.
    return Sort.by(direction, fieldFor(listFilesQuery.sortField())).and(Sort.by(direction, "_id"));
  }

  private static String fieldFor(SortField sortField) {
    return switch (sortField) {
      case FILENAME -> "filename";
      case UPLOAD_DATE -> "uploadedAt";
      case TAG -> "tags";
      case CONTENT_TYPE -> "contentType";
      case SIZE -> "size";
    };
  }
}
