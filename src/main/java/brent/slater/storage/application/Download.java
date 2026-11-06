package brent.slater.storage.application;

import java.io.InputStream;

import brent.slater.storage.domain.StoredFile;

public record Download(StoredFile file, InputStream content) {
}
