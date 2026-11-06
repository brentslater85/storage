package brent.slater.storage.domain;

public class DuplicateFileException extends RuntimeException {

  private DuplicateFileException(String message) {
    super(message);
  }

  public static DuplicateFileException sameFilename(String filename) {
    return new DuplicateFileException("You already have a file named '" + filename + "'");
  }

  public static DuplicateFileException sameContent() {
    return new DuplicateFileException("You already have a file with the same content");
  }
}
