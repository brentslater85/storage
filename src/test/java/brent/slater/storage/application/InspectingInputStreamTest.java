package brent.slater.storage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class InspectingInputStreamTest {

  private static final byte[] CONTENT = toBytes(IntStream.range(0, 300).map(index -> index * 7));

  @Test
  void passesEveryByteThroughUnchangedWhetherReadSinglyOrInBlocks() throws IOException {
    var singly = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    var blocks = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);

    assertThat(readByteByByte(singly)).isEqualTo(CONTENT);
    assertThat(blocks.readAllBytes()).isEqualTo(CONTENT);
  }

  @Test
  void fingerprintsTheContentWhetherReadSinglyOrInBlocks() throws Exception {
    var singly = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    var blocks = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    readByteByByte(singly);
    blocks.readAllBytes();

    var expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
    assertThat(singly.sha256Hex()).isEqualTo(expectedSha256);
    assertThat(blocks.sha256Hex()).isEqualTo(expectedSha256);
    assertThat(singly.bytesRead()).isEqualTo(CONTENT.length);
    assertThat(blocks.bytesRead()).isEqualTo(CONTENT.length);
  }

  @Test
  void keepsOnlyTheRequestedNumberOfLeadingBytes() throws IOException {
    var singly = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    var blocks = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    readByteByByte(singly);
    blocks.readAllBytes();

    assertThat(singly.leadingBytes()).isEqualTo(Arrays.copyOf(CONTENT, 16));
    assertThat(blocks.leadingBytes()).isEqualTo(Arrays.copyOf(CONTENT, 16));
  }

  @Test
  void fingerprintCanBeReadMoreThanOnce() throws IOException {
    var stream = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    stream.readAllBytes();

    assertThat(stream.sha256Hex()).isEqualTo(stream.sha256Hex());
  }

  @Test
  void neverSkipsOrRewindsSoNoByteEscapesInspection() throws IOException {
    var stream = new InspectingInputStream(new ByteArrayInputStream(CONTENT), 16);
    stream.mark(CONTENT.length);
    stream.readNBytes(10);

    assertThat(stream.markSupported()).isFalse();
    assertThatThrownBy(() -> stream.skip(10)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(stream::reset).isInstanceOf(IOException.class);
    assertThat(stream.bytesRead()).isEqualTo(10);
  }

  private static byte[] readByteByByte(InspectingInputStream stream) throws IOException {
    var output = new ByteArrayOutputStream();
    for (var value = stream.read(); value != -1; value = stream.read()) {
      output.write(value);
    }
    return output.toByteArray();
  }

  private static byte[] toBytes(IntStream values) {
    var output = new ByteArrayOutputStream();
    values.forEach(output::write);
    return output.toByteArray();
  }
}
