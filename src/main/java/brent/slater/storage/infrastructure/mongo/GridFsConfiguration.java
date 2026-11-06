package brent.slater.storage.infrastructure.mongo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;

@Configuration
class GridFsConfiguration {

  @Bean
  GridFSBucket fileContentBucket(MongoDatabaseFactory databases) {
    return GridFSBuckets.create(databases.getMongoDatabase(), "file_content");
  }
}
