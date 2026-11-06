package brent.slater.storage.application;

import java.util.List;

import brent.slater.storage.domain.StoredFile;

public record FilePage(List<StoredFile> files, int page, int size, long totalItems) {

  public int totalPages() {
    return (int) Math.ceilDiv(totalItems, size);
  }
}
