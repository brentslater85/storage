package brent.slater.storage.domain;

public final class UserIds {

  private UserIds() {
  }

  public static void validate(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new InvalidRequestException("A user id is required");
    }
  }
}
