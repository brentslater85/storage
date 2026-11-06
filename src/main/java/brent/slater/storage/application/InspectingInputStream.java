package brent.slater.storage.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

final class InspectingInputStream extends FilterInputStream {

  private final MessageDigest digest;
  private final byte[] leadingBytes;
  private int leadingBytesCaptured;
  private long bytesRead;
  private String sha256Hex;

  InspectingInputStream(InputStream source, int leadingBytesToCapture) {
    super(source);
    this.digest = sha256();
    this.leadingBytes = new byte[leadingBytesToCapture];
  }

  @Override
  public int read() throws IOException {
    var value = super.read();
    if (value != -1) {
      inspect(new byte[] {(byte) value}, 0, 1);
    }
    return value;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    var count = super.read(buffer, offset, length);
    if (count != -1) {
      inspect(buffer, offset, count);
    }
    return count;
  }

  @Override
  public long skip(long count) {
    throw new UnsupportedOperationException("Every byte must be inspected");
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void mark(int readLimit) {
  }

  @Override
  public void reset() throws IOException {
    throw new IOException("Every byte must be inspected once, so the stream cannot be rewound");
  }

  long bytesRead() {
    return bytesRead;
  }

  String sha256Hex() {
    if (sha256Hex == null) {
      sha256Hex = HexFormat.of().formatHex(digest.digest());
    }
    return sha256Hex;
  }

  byte[] leadingBytes() {
    return Arrays.copyOf(leadingBytes, leadingBytesCaptured);
  }

  private void inspect(byte[] buffer, int offset, int count) {
    digest.update(buffer, offset, count);
    var toCapture = Math.min(count, leadingBytes.length - leadingBytesCaptured);
    System.arraycopy(buffer, offset, leadingBytes, leadingBytesCaptured, toCapture);
    leadingBytesCaptured += toCapture;
    bytesRead += count;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Every JVM must support SHA-256", impossible);
    }
  }
}
