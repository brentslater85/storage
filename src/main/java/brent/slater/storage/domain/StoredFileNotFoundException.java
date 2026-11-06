package brent.slater.storage.domain;

public class StoredFileNotFoundException extends RuntimeException {

  public StoredFileNotFoundException() {
    super("File not found");
  }
}
