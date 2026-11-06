package brent.slater.storage.infrastructure.mongo;

import static org.springframework.data.domain.Sort.Direction.ASC;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class FileIndexes {

  static final String ONE_FILENAME_PER_OWNER = "one_filename_per_owner";
  static final String ONE_COPY_OF_CONTENT_PER_OWNER = "one_copy_of_content_per_owner";
  static final String DOWNLOAD_TOKEN = "download_token";

  private final MongoTemplate mongo;

  @PostConstruct
  void createIndexes() {
    var indexes = mongo.indexOps(FileDocument.class);
    indexes.createIndex(new Index()
        .named(ONE_FILENAME_PER_OWNER)
        .on("ownerId", ASC).on("filename", ASC)
        .unique());
    indexes.createIndex(new Index()
        .named(ONE_COPY_OF_CONTENT_PER_OWNER)
        .on("ownerId", ASC).on("sha256", ASC)
        .unique());
    indexes.createIndex(new Index()
        .named(DOWNLOAD_TOKEN)
        .on("downloadToken", ASC)
        .unique());
    indexes.createIndex(new Index().named("public_by_tag").on("visibility", ASC).on("tags", ASC));
    indexes.createIndex(new Index().named("owner_by_tag").on("ownerId", ASC).on("tags", ASC));
  }
}
