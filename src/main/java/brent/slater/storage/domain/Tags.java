package brent.slater.storage.domain;

import static java.util.function.Predicate.not;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record Tags(List<String> values) {

  private static final int MAXIMUM_PER_FILE = 5;

  public Tags {
    values = Optional.ofNullable(values).orElse(List.of()).stream()
        .map(Tags::normalise)
        .filter(not(String::isEmpty))
        .distinct()
        .toList();
  }

  public static Tags chosen(List<String> values) {
    var tags = new Tags(values);
    if (tags.values().size() > MAXIMUM_PER_FILE) {
      throw new InvalidRequestException("A file can have at most " + MAXIMUM_PER_FILE + " tags");
    }
    return tags;
  }

  public static String normalise(String tag) {
    return tag.strip().toLowerCase(Locale.ROOT);
  }
}
