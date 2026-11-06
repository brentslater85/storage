package brent.slater.storage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import brent.slater.storage.domain.DuplicateFileException;
import brent.slater.storage.domain.InvalidRequestException;
import brent.slater.storage.domain.NotFileOwnerException;
import brent.slater.storage.domain.StoredFileNotFoundException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler
  ProblemDetail invalidRequest(InvalidRequestException exception) {
    return problem(HttpStatus.BAD_REQUEST, exception);
  }

  @ExceptionHandler
  ProblemDetail duplicateFile(DuplicateFileException exception) {
    return problem(HttpStatus.CONFLICT, exception);
  }

  @ExceptionHandler
  ProblemDetail notFound(StoredFileNotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, exception);
  }

  @ExceptionHandler
  ProblemDetail notOwner(NotFileOwnerException exception) {
    return problem(HttpStatus.FORBIDDEN, exception);
  }

  private static ProblemDetail problem(HttpStatus status, RuntimeException exception) {
    return ProblemDetail.forStatusAndDetail(status, exception.getMessage());
  }
}
