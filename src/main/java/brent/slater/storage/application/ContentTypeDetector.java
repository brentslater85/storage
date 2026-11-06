package brent.slater.storage.application;

public interface ContentTypeDetector {

  String detect(byte[] leadingBytes, String filename);
}
