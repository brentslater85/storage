package brent.slater.storage.domain;

public final class Filenames {

  private static final int MAXIMUM_LENGTH = 255;

  private Filenames() {
  }

  public static void validate(String filename) {
    if (filename == null || filename.isBlank()) {
      throw new InvalidRequestException("A filename is required");
    }
    if (filename.length() > MAXIMUM_LENGTH) {
      throw new InvalidRequestException("A filename can be at most " + MAXIMUM_LENGTH + " characters");
    }
  }
}
