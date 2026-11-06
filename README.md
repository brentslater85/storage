# STORAGE

A REST service for storing and sharing files of any size, from a few KB to hundreds of GB.
Built with Spring Boot 4, Java 21 and MongoDB.

## Quick start

```bash
docker compose up --build
```

The API is then on `http://localhost:8080`. The `storage` container runs with a 1GB memory limit, a
read-only filesystem and a 200MB writable `/tmp`, matching the non-functional requirements.

```bash
# Upload (the request body is the file)
curl -X POST "localhost:8080/files?filename=holiday.jpg&visibility=PUBLIC&tags=Beach&tags=2026" \
  -H "X-User-Id: alice" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @holiday.jpg

# Download using the downloadUrl from the upload response
curl -OJ "localhost:8080/download/3q2-7wEAAAA..."

# List public files tagged "beach", biggest first
curl "localhost:8080/files/public?tag=beach&sort=size&direction=desc"
```

## API

There is no user management. The caller's identity is the `X-User-Id` header, which is required
wherever "the user" matters. Errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457).

### `POST /files` — upload

| Part | Description |
|---|---|
| `X-User-Id` header | Required. The uploading user |
| `filename` query | Required. 1–255 characters |
| `visibility` query | `PUBLIC` or `PRIVATE`. Defaults to `PRIVATE` |
| `tags` query | Repeat (`tags=a&tags=b`), comma-separate (`tags=a,b`), or both. At most 5. Case-insensitive |
| `Content-Type` header | Optional. If missing, a wildcard (`*/*`, `image/*`) or `application/octet-stream`, the type is detected from the content |
| Body | The raw file bytes. Not a form |

Responses:

- `201` with the file (below)
- `400` for a missing or blank user, a missing or over-long filename, an unknown visibility, or more than 5 tags
- `409` if the user already has a file with this filename or with identical content
- `415` for `application/x-www-form-urlencoded`, or a `Content-Type` that isn't a media type. Tomcat parses a
  form-encoded body as form fields as soon as any request parameter is read, which would silently store an
  empty file. `curl --data-binary` sends that type unless you set one. Other types, including
  `multipart/form-data`, are stored exactly as sent.

```json
{
  "id": "6f1c0a8e-0d5f-4c43-9a59-6a8f2f0c6f6e",
  "filename": "holiday.jpg",
  "visibility": "PUBLIC",
  "tags": ["beach", "2026"],
  "contentType": "image/jpeg",
  "size": 482113,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "uploadDate": "2026-11-04T10:15:30.123Z",
  "downloadUrl": "/download/Mn-9Pfn_k4xxPmmWaFndV1hnBsGpZHd3LiPn9blLq74"
}
```

| Field | Description |
|---|---|
| `id` | The file's id, used to rename or delete it |
| `filename` | As uploaded, or as last renamed |
| `visibility` | `PUBLIC` or `PRIVATE` |
| `tags` | Lower-cased, without duplicates |
| `contentType` | As sent by the client, or detected from the content |
| `size` | File size in **bytes** |
| `sha256` | Hex-encoded SHA-256 of the content |
| `uploadDate` | When the upload finished, in UTC (ISO-8601) |
| `downloadUrl` | Relative link that downloads the file. Anyone holding it can download |

### `GET /download/{token}` — download

Streams the file with `Content-Type`, `Content-Length`, `Content-Disposition: attachment` and
`X-Content-Type-Options: nosniff`. `HEAD` returns the same headers without reading the content.
Works for public and private files alike; holding the link is the permission. `404` for unknown tokens.

### `GET /files/public`, `GET /files/mine` and `GET /files/visible` — list

- `/files/public` lists every user's public files.
- `/files/mine` lists all of the caller's files, public and private.
- `/files/visible` lists everything the caller can see: every user's public files plus all of the caller's own.

`/files/mine` and `/files/visible` require `X-User-Id`.
`400` for an unknown sort field or direction, a negative page, or a page size outside 1–100. A page beyond the last is empty.

| Query | Default | Description |
|---|---|---|
| `tag` | none | Exact tag match, case-insensitive. `hol` does not match `holiday` |
| `sort` | `uploadDate` | `filename`, `uploadDate`, `tag`, `contentType` or `size` |
| `direction` | `desc` | `asc` or `desc` |
| `page` | `0` | Zero-based page number |
| `size` | `20` | Page size: the number of files per page, 1–100. Not to be confused with a file's `size` in bytes |

```json
{ "items": [ { "...": "file as above" } ], "page": 0, "size": 20, "totalItems": 42, "totalPages": 3 }
```

### `PATCH /files/{id}` — rename

Body `{"filename": "new-name.jpg"}`. Owner only. Changes metadata only; the content, hash and
download link are unchanged. `400` for a missing user or an invalid filename, `403` for someone else's file,
`404` for an unknown file, `409` if the user already has a file with that name.

### `DELETE /files/{id}` — delete

Owner only. `204` on success, `400` for a missing user, `403` for someone else's file, `404` for an unknown file.
The download link stops working. The content is removed before the file's record, so if a delete fails part-way
the file is still listed and retrying the delete finishes the job.

## Design

```
api             REST controllers, request/response shapes, error mapping
application     FileService use cases and the ports they need (FileCatalogue, BlobStore, ContentTypeDetector)
domain          StoredFile, Tags, Visibility, filename rules, domain exceptions
infrastructure  MongoDB catalogue, GridFS blob store, Apache Tika content detection
```

### Streaming, so file size doesn't matter

The upload body is read straight from the servlet input stream into a GridFS upload stream, which
writes 255KB chunks to MongoDB. Nothing is buffered to memory or local disk. Spring's multipart
support is disabled because it spools uploads to a temp file, which would break the 200MB disk limit.

As bytes pass through, one wrapper stream computes the SHA-256, counts the size and keeps the
first 64KB for content-type detection. That gives a single pass over the data however large it is.
Downloads stream back out of GridFS four chunks (about 1MB) at a time. Left to its default, MongoDB returns
batches of up to 16MB, and each open download holds one batch in memory.

If storing content fails part-way, whether the client disconnects or MongoDB errors, the chunks written so
far are deleted.

Requests run on virtual threads, so many slow, long-running uploads don't tie up a thread pool.

### Duplicate detection without locks

Files are unique **per user**: two users can upload the same name or content. MongoDB unique indexes
enforce this, so concurrent requests can't both win:

| Index | Purpose |
|---|---|
| `{ownerId, filename}` unique | One filename per user |
| `{ownerId, sha256}` unique | One copy of any content per user |
| `{downloadToken}` unique | Lookup for download links |

An upload has three steps:

1. **Check the filename.** If the user already has a file with that name, return `409` *before*
   reading the body, so nobody uploads 100GB only to be told the name is taken. Tomcat discards at most
   2MB of the unread body before closing the connection.
2. **Stream** the content to GridFS while hashing it.
3. **Insert** the `files` document with the hash, size and type. Duplicate content fails here,
   because the hash isn't known until the last byte. So does a duplicate filename from a concurrent
   upload that passed step 1 at the same moment. The losing upload deletes its stored content, then returns `409`.

Only completed uploads have a `files` document. Nothing in the catalogue is left half-finished when an
upload fails, and reads need no status filter. The trade-off: two uploads by the same user with the same
filename, at the same moment, both stream their content before one is rejected. That is rare and still
ends correctly, whereas reserving the filename up front would leave stale reservations that block the
name whenever an upload crashed.

### Download links

Each file gets a 256-bit token from `SecureRandom`, base64url-encoded, which can't be guessed. The token is
separate from the file id because file ids appear in public listings, so a link built from the id would
let anyone download private files.

### Content type

If the client sends a specific `Content-Type`, it is kept. Otherwise (no header, a wildcard, or
`application/octet-stream`) Apache Tika (`tika-core` only, which is
small) detects the type from the first 64KB plus the filename once the upload has finished.

## Testing

```bash
mvn verify
```

Tests drive the real HTTP API against a real MongoDB in Testcontainers (Docker required), with no
mocks. Each test creates its own user ids, so tests never reset the database or depend on each
other's data.

The submission checklist maps to these tests:

| Requirement | Test |
|---|---|
| Parallel upload, same filename | `DuplicateUploadTest.parallelUploadsWithTheSameFilenameStoreExactlyOneFile` |
| Parallel upload, same content | `DuplicateUploadTest.parallelUploadsWithTheSameContentStoreExactlyOneFile` |
| Upload of at least 2GB | `LargeFileUploadTest.uploadsAFileLargerThanTwoGigabytesWithoutHoldingItInMemory` |
| Delete someone else's file | `DeleteFileTest.userCannotDeleteAFileThatBelongsToSomeoneElse` |
| List all public files | `ListFilesTest.listsAllPublicFilesFromEveryUser` |
| Rename without re-upload | `RenameFileTest.renamingKeepsTheContentAndDownloadLinkWithoutReuploading` |

The 2GB test generates its content on the fly and streams it over a real HTTP connection, so the
file never exists on disk or in memory. Surefire runs tests with `-Xmx512m`; the test could not pass
if anything buffered the whole file.

`StoredContentTest` checks the storage itself, which the API can't show: deleting a file, rejecting
a duplicate, a client disconnecting mid-upload and MongoDB failing mid-upload all leave no content behind,
including orphaned GridFS chunks. `StorageFaults` makes MongoDB fail or stall chosen commands through its
`failCommand` failpoint, so those failures are real rather than mocked.

Mutation testing: `mvn test-compile org.pitest:pitest-maven:mutationCoverage`. CI runs it with a 99%
threshold; every mutant is currently killed. The large-file tests are excluded there for speed.

## Build and CI

- `mvn package` builds the jar. `docker build .` builds the image with a multi-stage Dockerfile, so no local Java is needed.
- GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` and mutation testing on every push and pull
  request, then builds the Docker image. Pushes to `main` publish the image to GitHub Container Registry
  from a separate job, the only one allowed to write packages. Actions are pinned to commit SHAs.

## Assumptions and decisions

- **Filenames are case-sensitive**: `Report.pdf` and `report.pdf` are different files. Tags are case-insensitive, as required.
- **Sorting by tag** uses MongoDB's array ordering: ascending uses each file's lowest tag, descending its highest.
- **Sorting by filename is case-sensitive**: uppercase sorts before lowercase, so `Zebra.txt` comes before
  `apple.txt`. A collation would give dictionary order, but MongoDB only uses an index for a query whose
  collation matches the index's, so every index would need rebuilding with it.
- **Stored files outlive rule changes.** Limits such as five tags are checked on upload, not when reading,
  so tightening a rule never makes existing files unreadable.
- **403 rather than 404** when changing someone else's file. File ids appear in public listings, so a 404 would hide nothing.
- **Listing.** The brief asks for listing "all PUBLIC" and "all files that belong to USER". These are two
  separate lists (`/files/public`, `/files/mine`), and `/files/visible` adds the combined view of everything
  a user can see, in case the brief meant that.
- **No tags endpoint.** Tags are created simply by using them, as the brief allows.

## What I'd do next

- **Clean up orphaned content.** If the process crashes after storing content in GridFS but before inserting
  its `files` document, or removing a rejected upload's content fails (this is logged), the content is left
  behind. A scheduled job should remove GridFS files older than a threshold that no `files` document references.
- **Range requests** on downloads, so large downloads can resume.
- **A real identity provider** in place of the trusted `X-User-Id` header.
- **Object storage** (S3 or MinIO) behind the existing `BlobStore` port, if MongoDB storage becomes a cost or scaling issue.
