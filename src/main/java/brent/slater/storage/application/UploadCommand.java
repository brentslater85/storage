package brent.slater.storage.application;

import java.util.Optional;

import brent.slater.storage.domain.Filenames;
import brent.slater.storage.domain.Tags;
import brent.slater.storage.domain.UserIds;
import brent.slater.storage.domain.Visibility;

public record UploadCommand(
    String ownerId,
    String filename,
    Visibility visibility,
    Tags tags,
    Optional<String> declaredContentType) {

  public UploadCommand {
    UserIds.validate(ownerId);
    Filenames.validate(filename);
  }
}
