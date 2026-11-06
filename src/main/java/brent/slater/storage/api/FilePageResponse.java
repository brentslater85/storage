package brent.slater.storage.api;

import java.util.List;

public record FilePageResponse(List<FileResponse> items, int page, int size, long totalItems, int totalPages) {
}
