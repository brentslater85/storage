package brent.slater.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import brent.slater.storage.StorageClient.Upload;

class LargeFileUploadTest extends StorageApiTest {

  private static final long TWO_GIGABYTES_AND_A_BIT = 2L * 1024 * 1024 * 1024 + 1;
  private static final int CONCURRENT_DOWNLOADS = 50;
  private static final int SLOW_READER_BYTES = 64 * 1024;

  @Test
  void uploadsAFileLargerThanTwoGigabytesWithoutHoldingItInMemory() throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var content = new DigestInputStream(new GeneratedContent(TWO_GIGABYTES_AND_A_BIT), digest);
    var filename = uniqueName("huge.bin");

    var uploaded = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/files?filename=" + filename))
            .header(StorageClient.USER_HEADER, newUserId())
            .POST(BodyPublishers.fromPublisher(
                BodyPublishers.ofInputStream(() -> content), TWO_GIGABYTES_AND_A_BIT))
            .build(),
        BodyHandlers.ofString());

    assertThat(uploaded.statusCode()).isEqualTo(201);
    assertThat(uploaded.body())
        .contains("\"size\":" + TWO_GIGABYTES_AND_A_BIT)
        .contains("\"sha256\":\"" + HexFormat.of().formatHex(digest.digest()) + "\"");
  }

  @Test
  void largeFileCanBeDownloaded() throws Exception {
    var size = 64L * 1024 * 1024;
    var content = new GeneratedContent(size).readAllBytes();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("big.bin"), content));

    var download = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + file.downloadUrl())).build(),
        BodyHandlers.ofInputStream());

    try (var body = download.body()) {
      var digest = MessageDigest.getInstance("SHA-256");
      body.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
      assertThat(download.headers().firstValueAsLong("Content-Length")).hasValue(size);
      assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(file.sha256());
    }
  }

  @Test
  void manyPeopleCanDownloadALargeFileAtOnceWithoutExhaustingMemory() throws Exception {
    var size = 32 * 1024 * 1024;
    var expectedStart = new GeneratedContent(SLOW_READER_BYTES).readAllBytes();
    var content = new GeneratedContent(size).readAllBytes();
    var file = storage.uploaded(Upload.of(newUserId(), uniqueName("popular.bin"), content));
    var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + file.downloadUrl())).build();
    var slowReaders = new ArrayList<InputStream>();

    try {
      for (var reader = 0; reader < CONCURRENT_DOWNLOADS; reader++) {
        var download = client.send(request, BodyHandlers.ofInputStream());
        slowReaders.add(download.body());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body().readNBytes(SLOW_READER_BYTES)).isEqualTo(expectedStart);
      }
    } finally {
      for (var reader : slowReaders) {
        reader.close();
      }
    }
  }

  private static final class GeneratedContent extends InputStream {

    private final long size;
    private long position;

    GeneratedContent(long size) {
      this.size = size;
    }

    @Override
    public int read() {
      if (position >= size) {
        return -1;
      }
      return byteAt(position++) & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (position >= size) {
        return -1;
      }
      var count = (int) Math.min(length, size - position);
      for (var index = 0; index < count; index++) {
        buffer[offset + index] = byteAt(position++);
      }
      return count;
    }

    private static byte byteAt(long position) {
      return (byte) (position ^ (position >>> 8) ^ (position >>> 19));
    }
  }
}
