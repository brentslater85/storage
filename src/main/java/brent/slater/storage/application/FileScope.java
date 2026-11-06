package brent.slater.storage.application;

import brent.slater.storage.domain.UserIds;

public sealed interface FileScope {

  record PublicFiles() implements FileScope {
  }

  record OwnedBy(String ownerId) implements FileScope {

    public OwnedBy {
      UserIds.validate(ownerId);
    }
  }

  record VisibleTo(String userId) implements FileScope {

    public VisibleTo {
      UserIds.validate(userId);
    }
  }
}
