package brent.slater.storage.application;

import static java.util.stream.Collectors.joining;

import java.util.Arrays;

import brent.slater.storage.domain.InvalidRequestException;

public enum SortField {
  FILENAME("filename"),
  UPLOAD_DATE("uploadDate"),
  TAG("tag"),
  CONTENT_TYPE("contentType"),
  SIZE("size");

  private final String apiName;

  SortField(String apiName) {
    this.apiName = apiName;
  }

  public static SortField fromApiName(String requested) {
    return Arrays.stream(values())
        .filter(field -> field.apiName.equalsIgnoreCase(requested))
        .findFirst()
        .orElseThrow(() -> new InvalidRequestException(
            "Cannot sort by '" + requested + "'. Sort by one of: "
                + Arrays.stream(values()).map(field -> field.apiName).collect(joining(", "))));
  }
}
