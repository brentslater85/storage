package brent.slater.storage.application;

public record StoredContent(String blobId, long size, String sha256, String contentType) {
}
