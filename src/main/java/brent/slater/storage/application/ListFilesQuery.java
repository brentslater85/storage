package brent.slater.storage.application;

import java.util.Optional;

import brent.slater.storage.domain.InvalidRequestException;
import brent.slater.storage.domain.Tags;

public record ListFilesQuery(
    FileScope scope,
    Optional<String> tag,
    SortField sortField,
    SortDirection direction,
    int page,
    int size) {

  private static final int MAXIMUM_PAGE_SIZE = 100;

  public ListFilesQuery {
    tag = tag.map(Tags::normalise).filter(normalised -> !normalised.isEmpty());
    if (page < 0) {
      throw new InvalidRequestException("Page numbers start at 0");
    }
    if (size < 1 || size > MAXIMUM_PAGE_SIZE) {
      throw new InvalidRequestException("Page size must be between 1 and " + MAXIMUM_PAGE_SIZE);
    }
  }
}
