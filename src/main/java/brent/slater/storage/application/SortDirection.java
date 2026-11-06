package brent.slater.storage.application;

import java.util.Arrays;

import brent.slater.storage.domain.InvalidRequestException;

public enum SortDirection {
  ASC,
  DESC;

  public static SortDirection fromApiName(String requested) {
    return Arrays.stream(values())
        .filter(direction -> direction.name().equalsIgnoreCase(requested))
        .findFirst()
        .orElseThrow(() -> new InvalidRequestException("Sort direction must be 'asc' or 'desc'"));
  }
}
