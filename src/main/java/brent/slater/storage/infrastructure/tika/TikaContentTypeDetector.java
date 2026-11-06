package brent.slater.storage.infrastructure.tika;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import brent.slater.storage.application.ContentTypeDetector;

@Component
class TikaContentTypeDetector implements ContentTypeDetector {

  private final Tika tika = new Tika();

  @Override
  public String detect(byte[] leadingBytes, String filename) {
    return tika.detect(leadingBytes, filename);
  }
}
