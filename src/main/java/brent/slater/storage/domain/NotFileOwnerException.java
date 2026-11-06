package brent.slater.storage.domain;

public class NotFileOwnerException extends RuntimeException {

  public NotFileOwnerException() {
    super("Only the owner of a file can change it");
  }
}
